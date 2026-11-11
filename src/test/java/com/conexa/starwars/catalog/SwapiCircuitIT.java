package com.conexa.starwars.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import com.github.tomakehurst.wiremock.http.Fault;
import com.jayway.jsonpath.JsonPath;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

@AutoConfigureMetrics
@Import(SwapiCircuitIT.Time.class)
@ExtendWith(OutputCaptureExtension.class)
class SwapiCircuitIT extends CatalogIntegrationSupport {
    @Autowired MutableClock clock;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void resetClock() {
        clock.now = Instant.now();
    }

    @Override
    protected String accessToken() {
        return token(
                "catalog:read", "test-issuer", "test-audience", clock.instant().plusSeconds(900));
    }

    @Test
    void opensRejectsWithoutNetworkThenRecoversAfterTwoValidProbes(CapturedOutput output)
            throws Exception {
        failProvider();
        assertOpen();
        assertCode("people", "SWAPI_CIRCUIT_OPEN");
        assertCode("vehicles", "SWAPI_CIRCUIT_OPEN");
        assertThat(swapi.getAllServeEvents()).hasSize(5);
        swapi.stubFor(
                get(urlEqualTo("/api/people/1")).willReturn(okJson(fixture("people-detail"))));
        clock.advance(Duration.ofSeconds(9));
        assertCode("people", "SWAPI_CIRCUIT_OPEN");
        assertThat(swapi.getAllServeEvents()).hasSize(5);
        clock.advance(Duration.ofSeconds(2));
        assertThat(request("?id=1").statusCode()).isEqualTo(200);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(request("?id=1").statusCode()).isEqualTo(200);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(swapi.getAllServeEvents()).hasSize(7);
        assertStateMetric("closed");
        assertThat(output.getAll())
                .contains(
                        "swapi.circuit.transition",
                        "\"toState\":\"OPEN\"",
                        "\"toState\":\"HALF_OPEN\"",
                        "\"toState\":\"CLOSED\"");
    }

    @Test
    void failedHalfOpenProbesReopenTheCircuit() throws Exception {
        failProvider();
        clock.advance(Duration.ofSeconds(11));
        assertThat(request("?id=1").statusCode()).isEqualTo(503);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        assertThat(request("?id=1").statusCode()).isEqualTo(503);
        assertOpen();
        assertCode("people", "SWAPI_CIRCUIT_OPEN");
        assertThat(swapi.getAllServeEvents()).hasSize(7);
    }

    @Test
    void confirmedAbsencesAndIncompatibleResponsesAreIgnored() throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/people/1"))
                        .willReturn(
                                aResponse()
                                        .withStatus(404)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"message\":\"not found\"}")));
        for (int i = 0; i < 6; i++) assertThat(request("?id=1").statusCode()).isEqualTo(200);
        swapi.stubFor(
                get(urlPathEqualTo("/api/people/"))
                        .willReturn(okJson(fixture("people-name-missing"))));
        for (int i = 0; i < 6; i++)
            assertThat(request("?name=missing").statusCode()).isEqualTo(200);
        swapi.stubFor(
                get(urlEqualTo("/api/people/1"))
                        .willReturn(
                                okJson(
                                        "{\"message\":\"ok\",\"result\":{\"uid\":\"1\",\"properties\":{}}}")));
        for (int i = 0; i < 6; i++) assertThat(request("?id=1").statusCode()).isEqualTo(502);
        swapi.stubFor(
                get(urlPathEqualTo("/api/people"))
                        .willReturn(okJson("{\"message\":\"ok\",\"results\":[]}")));
        for (int i = 0; i < 6; i++) assertThat(request("").statusCode()).isEqualTo(502);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void cachedFilmsRemainAvailableWhileCircuitIsOpenAndDoNotUpdateIt() throws Exception {
        swapi.stubFor(get(urlEqualTo("/api/films")).willReturn(okJson(fixture("films-page-2"))));
        assertThat(request("films", "?page=1&size=2").statusCode()).isEqualTo(200);
        swapi.stubFor(get(urlEqualTo("/api/people/1")).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 4; i++) assertThat(request("?id=1").statusCode()).isEqualTo(503);
        assertOpen();
        int buffered = circuit.getMetrics().getNumberOfBufferedCalls();
        long rejected = circuit.getMetrics().getNumberOfNotPermittedCalls();
        assertThat(request("films", "?page=2&size=2").statusCode()).isEqualTo(200);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isEqualTo(buffered);
        assertThat(circuit.getMetrics().getNumberOfNotPermittedCalls()).isEqualTo(rejected);
        assertThat(swapi.getAllServeEvents()).hasSize(5);
        filmsCache.clear();
        var response = request("films", "");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(response.body(), "$.code"))
                .isEqualTo("SWAPI_CIRCUIT_OPEN");
        assertThat(swapi.getAllServeEvents()).hasSize(5);
    }

    @Test
    void timeoutsAndConnectionFailuresCountWithoutChangingClientTimeouts() throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/people/1"))
                        .willReturn(okJson(fixture("people-detail")).withFixedDelay(3500)));
        var response = request("?id=1");
        assertThat(response.statusCode()).isEqualTo(504);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo("CATALOG_TIMEOUT");
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
        swapi.stubFor(
                get(urlEqualTo("/api/people/1"))
                        .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        assertThat(request("?id=1").statusCode()).isEqualTo(503);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    void providerQuotaIsCountedButLocalRejectionsAreNot() throws Exception {
        assertThat(request("?page=0").statusCode()).isEqualTo(400);
        assertThat(rawGet("/api/v1/people", null).statusCode()).isEqualTo(401);
        String denied =
                token(
                        "profile:read",
                        "test-issuer",
                        "test-audience",
                        clock.instant().plusSeconds(900));
        assertThat(rawGet("/api/v1/people", denied).statusCode()).isEqualTo(403);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
        for (int i = 0; i < 60; i++) quotas.catalog(USERNAME);
        assertThat(request("?id=1").statusCode()).isEqualTo(429);
        assertThat(circuit.getMetrics().getNumberOfBufferedCalls()).isZero();
        assertThat(swapi.getAllServeEvents()).isEmpty();
        quotas.clear();
        swapi.stubFor(get(urlEqualTo("/api/people/1")).willReturn(aResponse().withStatus(429)));
        assertThat(request("?id=1").statusCode()).isEqualTo(503);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isEqualTo(1);
    }

    private void assertCode(String resource, String code) throws Exception {
        var response = request(resource, "?id=1");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(JsonPath.<String>read(response.body(), "$.code")).isEqualTo(code);
    }

    private void failProvider() throws Exception {
        swapi.stubFor(get(urlEqualTo("/api/people/1")).willReturn(aResponse().withStatus(503)));
        for (int i = 0; i < 5; i++) assertCode("people", "CATALOG_UNAVAILABLE");
        assertThat(swapi.getAllServeEvents()).hasSize(5);
    }

    private void assertOpen() {
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertStateMetric("open");
    }

    private void assertStateMetric(String state) {
        assertThat(
                        meters.get("resilience4j.circuitbreaker.state")
                                .tag("name", "swapi")
                                .tag("state", state)
                                .gauge()
                                .value())
                .isEqualTo(1);
    }

    @TestConfiguration
    static class Time {
        @Bean
        @Primary
        MutableClock circuitTestClock() {
            return new MutableClock();
        }
    }

    static class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
