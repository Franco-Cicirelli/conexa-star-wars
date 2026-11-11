package com.conexa.starwars.telemetry;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@ActiveProfiles("observability")
@AutoConfigureMetrics
@TestPropertySource(
        properties = {
            "management.server.port=0",
            "management.tracing.export.otlp.enabled=false",
            "management.otlp.metrics.export.enabled=false"
        })
class LocalManagementIT extends CatalogIntegrationSupport {
    @Value("${local.management.port}")
    int managementPort;

    @Value("${management.server.address}")
    String managementAddress;

    @Test
    void metricsAreOnlyAvailableOnTheSeparateLoopbackManagementServer() throws Exception {
        assertThat(managementAddress).isEqualTo("127.0.0.1");
        assertThat(managementPort).isNotEqualTo(port);
        assertThat(rawGet("/api/v1", null).statusCode()).isEqualTo(200);
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://127.0.0.1:"
                                                            + managementPort
                                                            + "/actuator/prometheus"))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("http_server_requests_seconds_count");
        }
        assertThat(rawGet("/actuator/prometheus", null).statusCode()).isNotEqualTo(200);
        assertThat(rawGet("/actuator/metrics", accessToken()).statusCode()).isNotEqualTo(200);
    }

    @Test
    void livenessAndReadinessStayUpWhenSwapiIsUnavailable() throws Exception {
        swapi.stubFor(get(anyUrl()).willReturn(aResponse().withStatus(503)));
        assertThat(rawGet("/livez", null).statusCode()).isEqualTo(200);
        assertThat(rawGet("/readyz", null).statusCode()).isEqualTo(200);
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }
}
