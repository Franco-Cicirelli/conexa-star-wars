package com.conexa.starwars.support;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.conexa.starwars.catalog.infrastructure.swapi.FilmsCache;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistrar;

@SpringBootTest(
        classes = com.conexa.starwars.ConexaStarWarsApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.http.clients.read-timeout=3s")
@Import(CatalogIntegrationSupport.Provider.class)
public abstract class CatalogIntegrationSupport extends IntegrationSupport {
    @Autowired protected WireMockServer swapi;
    @Autowired protected FilmsCache filmsCache;

    @Value("${local.server.port}")
    protected int port;

    @BeforeEach
    void resetProvider() {
        swapi.resetAll();
        filmsCache.clear();
    }

    protected String fixture() throws Exception {
        return fixture("people-page-2");
    }

    protected String fixture(String name) throws Exception {
        try (var input =
                Objects.requireNonNull(
                        getClass().getResourceAsStream("/swapi/" + name + ".json"))) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    protected HttpResponse<String> request(String query) throws Exception {
        return request("people", query);
    }

    protected HttpResponse<String> request(String resource, String query) throws Exception {
        return requestPath("/" + resource + query);
    }

    protected HttpResponse<String> requestPath(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/api/v1" + path))
                            .header("Authorization", "Bearer " + accessToken())
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    protected HttpResponse<String> rawGet(String path, String token) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            var builder =
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
            if (token != null) builder.header("Authorization", "Bearer " + token);
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    protected String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Provider {
        @Bean(destroyMethod = "stop")
        WireMockServer swapi() {
            var server = new WireMockServer(options().dynamicPort());
            server.start();
            return server;
        }

        @Bean
        DynamicPropertyRegistrar providerProperties(WireMockServer server) {
            return registry -> registry.add("swapi.base-url", () -> server.baseUrl() + "/api");
        }
    }
}
