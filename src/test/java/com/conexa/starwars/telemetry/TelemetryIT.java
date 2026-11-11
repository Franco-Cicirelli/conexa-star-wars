package com.conexa.starwars.telemetry;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.conexa.starwars.support.CatalogIntegrationSupport;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

@org.junit.jupiter.api.extension.ExtendWith(
        org.springframework.boot.test.system.OutputCaptureExtension.class)
@AutoConfigureTracing
@AutoConfigureMetrics
@Import(TelemetryIT.Traces.class)
@TestPropertySource(properties = "management.tracing.sampling.probability=1.0")
class TelemetryIT extends CatalogIntegrationSupport {
    @Autowired MeterRegistry meters;
    @Autowired CapturedSpans spans;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(HttpObservationLoggingHandler.class);

    @BeforeEach
    void capture() {
        spans.values.clear();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    void incomingAndOutgoingSpansShareTraceAndPropagateToSwapi(
            org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        swapi.stubFor(get(urlPathEqualTo("/api/people")).willReturn(okJson(fixture())));
        assertThat(request("?page=2&size=2").statusCode()).isEqualTo(200);
        SpanData outgoing = span(SpanKind.CLIENT);
        SpanData incoming = span(SpanKind.SERVER);
        assertThat(outgoing.getTraceId()).isEqualTo(incoming.getTraceId());
        assertThat(outgoing.getParentSpanId()).isEqualTo(incoming.getSpanId());
        swapi.verify(
                getRequestedFor(urlPathEqualTo("/api/people"))
                        .withHeader(
                                "traceparent",
                                matching(
                                        "00-"
                                                + incoming.getTraceId()
                                                + "-"
                                                + outgoing.getSpanId()
                                                + "-[0-9a-f]{2}")));
        assertThat(logs.list).hasSize(2);
        assertThat(output.getAll()).doesNotContain("failed to append", "already been written");
        assertThat(output.getAll()).contains("swapi.client.completed", "http.server.completed");
        for (var event : logs.list) {
            assertThat(event.getMDCPropertyMap().get("traceId")).isEqualTo(incoming.getTraceId());
            assertThat(event.getKeyValuePairs())
                    .anySatisfy(
                            pair -> {
                                assertThat(pair.key).isEqualTo("status");
                                assertThat(pair.value).isEqualTo("200");
                            });
        }
        assertThat(
                        meters.find("http.server.requests")
                                .tag("uri", "/api/v1/people")
                                .tag("status", "200")
                                .timer())
                .isNotNull();
        assertThat(
                        meters.find("http.client.requests")
                                .tag("uri", "/people")
                                .tag("status", "200")
                                .timer())
                .isNotNull();
    }

    @Test
    void changingUidAndNameDoesNotCreateValueSpecificMetricTags() throws Exception {
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/1")).willReturn(okJson(fixture("people-detail"))));
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/11"))
                        .willReturn(
                                okJson(
                                        fixture("people-detail")
                                                .replace("\"uid\": \"1\"", "\"uid\": \"11\""))));
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/"))
                        .willReturn(okJson(fixture("people-name-missing"))));
        assertThat(request("?id=1").statusCode()).isEqualTo(200);
        assertThat(request("?id=11").statusCode()).isEqualTo(200);
        assertThat(request("?name=unique-search-sentinel").statusCode()).isEqualTo(200);
        for (var meter : meters.getMeters()) {
            if (!meter.getId().getName().startsWith("http.")) continue;
            for (var tag : meter.getId().getTags()) {
                assertThat(tag.getKey()).isNotIn("username", "name", "uid", "id");
                assertThat(tag.getValue())
                        .doesNotContain("unique-search-sentinel", "?id=", "?name=");
                if (tag.getKey().equals("uri")) {
                    assertThat(tag.getValue()).isNotIn("/people/1", "/people/11");
                }
            }
        }
        for (var span : spans.values) {
            assertThat(span.getName() + span.getAttributes())
                    .doesNotContain("unique-search-sentinel", "?name=", "?id=");
        }
    }

    @Test
    void credentialsAndHeadersAreNotLoggedOrExported() throws Exception {
        String password = "sensitive-password-sentinel-123";
        String token = accessToken();
        swapi.stubFor(
                get(urlPathEqualTo("/api/people"))
                        .willReturn(
                                aResponse().withStatus(503).withBody("external-sensitive-body")));
        assertThat(request("").statusCode()).isEqualTo(503);
        try (var client = HttpClient.newHttpClient()) {
            // An invalid username makes validation fail; no registration is created.
            var body = "{\"username\":\"bad user\",\"password\":\"" + password + "\"}";
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/auth/register"))
                                    .header("Content-Type", "application/json")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Cookie", "sensitive-cookie-sentinel")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(400);
        }
        String logsText =
                logs.list.stream()
                        .map(e -> e.getFormattedMessage() + e.getKeyValuePairs())
                        .collect(java.util.stream.Collectors.joining());
        assertNoSecrets(logsText, password, token);
        for (var span : spans.values) {
            String text = span.getName() + span.getAttributes() + span.getEvents();
            assertNoSecrets(text, password, token);
        }
    }

    @Test
    void livenessDoesNotCallSwapiAndPublicMetricsAreNotAvailable() throws Exception {
        swapi.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));
        assertThat(rawGet("/actuator/health/liveness", null).statusCode()).isEqualTo(200);
        assertThat(rawGet("/actuator/metrics", null).statusCode()).isNotEqualTo(200);
        assertThat(rawGet("/actuator/prometheus", accessToken()).statusCode()).isNotEqualTo(200);
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    private void assertNoSecrets(String text, String password, String token) {
        for (String secret :
                List.of(
                        password,
                        token,
                        "sensitive-cookie-sentinel",
                        "bad user",
                        "external-sensitive-body")) {
            assertThat(text.contains(secret))
                    .as("Telemetry must not expose sensitive data")
                    .isFalse();
        }
    }

    private SpanData span(SpanKind kind) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            var found = spans.values.stream().filter(span -> span.getKind() == kind).findFirst();
            if (found.isPresent()) return found.get();
            Thread.sleep(10);
        }
        throw new AssertionError("No span captured for " + kind);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Traces {
        @Bean
        CapturedSpans capturedSpans() {
            return new CapturedSpans();
        }

        @Bean
        SpanProcessor testSpans(CapturedSpans spans) {
            return SimpleSpanProcessor.create(spans);
        }
    }

    static class CapturedSpans implements SpanExporter {
        final List<SpanData> values = new CopyOnWriteArrayList<>();

        @Override
        public CompletableResultCode export(Collection<SpanData> batch) {
            values.addAll(batch);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
