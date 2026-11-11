package com.conexa.starwars;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConexaStarWarsApplicationIT extends com.conexa.starwars.support.IntegrationSupport {

    @Value("${local.server.port}")
    private int port;

    @Test
    void apiRespondsOverHttp() throws Exception {
        var response = get("/api/v1");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .contains("application/json");
        assertThat(response.body())
                .contains(
                        "\"application\":\"conexa-star-wars\"", "\"message\":\"API ready to use\"");
    }

    @Test
    void healthIsUp() throws Exception {
        var response = get("/actuator/health");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(response.body(), "$.status"))
                .isEqualTo("UP");
    }

    private HttpResponse<String> get(String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
