package com.conexa.starwars.catalog;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;

@AutoConfigureMetrics
@EnabledIfSystemProperty(named = "cache.benchmark", matches = "true")
class FilmsCacheBenchmarkIT extends CatalogIntegrationSupport {
    @Autowired MeterRegistry meters;

    private static final List<String> QUERIES =
            List.of(
                    "?page=1&size=2",
                    "?page=2&size=2",
                    "?name=hope&page=1&size=1",
                    "?page=3&size=2");

    @Test
    void compareRepeatedColdLoadsWithCacheReuse() throws Exception {
        swapi.stubFor(
                get(urlEqualTo("/api/films"))
                        .willReturn(okJson(fixture("films-page-2")).withFixedDelay(100)));
        Map<String, String> bodies = new HashMap<>();
        var baseline = new ArrayList<Double>();
        var cold = new ArrayList<Double>();
        var warm = new ArrayList<Double>();
        int baselineCalls = 0;
        int cachedCalls = 0;
        double hits = 0;
        double misses = 0;
        String token = accessToken();
        try (var client = HttpClient.newHttpClient()) {
            // Warm up HTTP/JWT/serialization before either measured scenario.
            for (int i = 0; i < 8; i++) measure(client, token, QUERIES.get(i % 4), bodies);
            for (int round = 0; round < 3; round++) {
                // Alternate order to reduce systematic JVM warmup/order bias.
                for (boolean cached :
                        round % 2 == 0 ? List.of(false, true) : List.of(true, false)) {
                    filmsCache.clear();
                    swapi.resetRequests();
                    double hitsBefore = gets("hit");
                    double missesBefore = gets("miss");
                    for (int i = 0; i < 20; i++) {
                        if (!cached) filmsCache.clear();
                        double elapsed = measure(client, token, QUERIES.get(i % 4), bodies);
                        if (!cached) baseline.add(elapsed);
                        else if (i == 0) cold.add(elapsed);
                        else warm.add(elapsed);
                    }
                    int calls = swapi.getAllServeEvents().size();
                    assertThat(calls).isEqualTo(cached ? 1 : 20);
                    if (cached) {
                        cachedCalls += calls;
                        hits += gets("hit") - hitsBefore;
                        misses += gets("miss") - missesBefore;
                    } else baselineCalls += calls;
                }
            }
        }
        assertThat(hits).isEqualTo(57);
        assertThat(misses).isEqualTo(3);
        String report =
                "# Films cache measurement\n\nCaptured at: "
                        + Instant.now()
                        + "\n\nJava: "
                        + System.getProperty("java.version")
                        + "; OS: "
                        + System.getProperty("os.name")
                        + " "
                        + System.getProperty("os.arch")
                        + "\n\nLocal authenticated HTTP requests, concurrency 1, 100 ms WireMock delay, "
                        + "8 warmup requests excluded, three rounds of 20 requests per scenario, alternating scenario order. "
                        + "Four queries cycle across three pages and a name filter. All responses are 200 and identical between scenarios. "
                        + "Each reuse round starts cold. No public SWAPI requests or telemetry exporters. "
                        + "The baseline invalidates before each request to emulate repeated Release 1 loads; this is not a separate Release 1 binary.\n\n"
                        + "| Scenario | Requests | SWAPI calls | Mean ms | p50 ms | p95 ms |\n"
                        + "| --- | ---: | ---: | ---: | ---: | ---: |\n"
                        + row("Repeated cold loads", baseline, baselineCalls)
                        + row("Cache cold (first request)", cold, cachedCalls)
                        + row("Cache warm", warm, 0)
                        + "\nCache reuse totals: "
                        + (int) hits
                        + " hits, "
                        + (int) misses
                        + " misses.\n"
                        + "\nReproduce: `./mvnw -B -Dcache.benchmark=true -Dit.test=FilmsCacheBenchmarkIT verify`\n"
                        + "\nThese are local measurements with simulated provider latency, not production performance guarantees.\n";
        Files.writeString(Path.of("target/films-cache-benchmark.md"), report);
    }

    private double measure(
            HttpClient client, String token, String query, Map<String, String> bodies)
            throws Exception {
        var request =
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/v1/films" + query))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build();
        long start = System.nanoTime();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        double elapsed = (System.nanoTime() - start) / 1_000_000.0;
        assertThat(response.statusCode()).isEqualTo(200);
        String previous = bodies.putIfAbsent(query, response.body());
        if (previous != null) assertThat(response.body()).isEqualTo(previous);
        return elapsed;
    }

    private double gets(String result) {
        return meters
                .find("cache.gets")
                .tag("resource", "films")
                .tag("result", result)
                .functionCounters()
                .stream()
                .mapToDouble(counter -> counter.count())
                .sum();
    }

    private static String row(String label, List<Double> values, int calls) {
        var sorted = values.stream().sorted().toList();
        return String.format(
                java.util.Locale.ROOT,
                "| %s | %d | %d | %.3f | %.3f | %.3f |%n",
                label,
                values.size(),
                calls,
                values.stream().mapToDouble(Double::doubleValue).average().orElseThrow(),
                sorted.get((int) Math.ceil(values.size() * 0.5) - 1),
                sorted.get((int) Math.ceil(values.size() * 0.95) - 1));
    }
}
