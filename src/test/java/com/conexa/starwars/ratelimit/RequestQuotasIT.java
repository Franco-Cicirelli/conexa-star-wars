package com.conexa.starwars.ratelimit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.conexa.starwars.auth.service.LoginService;
import com.conexa.starwars.support.CatalogIntegrationSupport;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

class RequestQuotasIT extends CatalogIntegrationSupport {
    @MockitoSpyBean LoginService loginService;

    @Test
    void cachedHitsConsumeQuotaAcrossCatalogRoutesAndUsersStayIndependent() throws Exception {
        swapi.stubFor(get(urlEqualTo("/api/films")).willReturn(okJson(fixture("films-page-2"))));
        String alice = signed("alice");
        String bob = signed("bob");
        for (int i = 0; i < 60; i++) {
            assertThat(rawGet("/api/v1/films?page=" + (i % 3 + 1) + "&size=2", alice).statusCode())
                    .isEqualTo(200);
        }
        assertThat(swapi.getAllServeEvents()).hasSize(1);
        for (String resource : List.of("people", "films", "starships", "vehicles")) {
            assertLimited(rawGet("/api/v1/" + resource, alice));
        }
        assertThat(swapi.getAllServeEvents()).hasSize(1);
        assertThat(rawGet("/api/v1/films", bob).statusCode()).isEqualTo(200);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void unauthorizedQueriesDoNotConsumeCatalogQuota() throws Exception {
        String denied =
                token(
                        "profile:read",
                        "test-issuer",
                        "test-audience",
                        Instant.now().plusSeconds(900));
        for (int i = 0; i < 61; i++) {
            assertThat(rawGet("/api/v1/films", null).statusCode()).isEqualTo(401);
            assertThat(rawGet("/api/v1/films", denied).statusCode()).isEqualTo(403);
        }
        assertThat(swapi.getAllServeEvents()).isEmpty();
        swapi.stubFor(get(urlEqualTo("/api/films")).willReturn(okJson(fixture("films-page-2"))));
        String valid = accessToken();
        for (int i = 0; i < 60; i++)
            assertThat(rawGet("/api/v1/films", valid).statusCode()).isEqualTo(200);
        assertLimited(rawGet("/api/v1/films", valid));
        // Security still wins over quota for invalid and insufficient-scope tokens.
        assertThat(rawGet("/api/v1/films", null).statusCode()).isEqualTo(401);
        assertThat(rawGet("/api/v1/films", denied).statusCode()).isEqualTo(403);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void loginRejectsBeforeAuthenticationAndIgnoresSpoofedProxyHeaders() throws Exception {
        for (int i = 0; i < 5; i++)
            assertThat(login("unknown-" + i, "192.0.2." + i).statusCode()).isEqualTo(401);
        clearInvocations(loginService);
        assertLimited(login("another-identity", "198.51.100.7"));
        verifyNoInteractions(loginService);
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    private HttpResponse<String> login(String username, String forwardedFor) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                            .header("Content-Type", "application/json")
                            .header("X-Forwarded-For", forwardedFor)
                            .POST(
                                    HttpRequest.BodyPublishers.ofString(
                                            "{\"username\":\""
                                                    + username
                                                    + "\",\"password\":\"incorrect-password\"}"))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }

    private String signed(String subject) {
        var now = Instant.now();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                                JwtClaimsSet.builder()
                                        .subject(subject)
                                        .issuer("test-issuer")
                                        .audience(List.of("test-audience"))
                                        .issuedAt(now)
                                        .expiresAt(now.plusSeconds(900))
                                        .claim("scope", "catalog:read")
                                        .build()))
                .getTokenValue();
    }

    private void assertLimited(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.headers().firstValue("Retry-After")).contains("60");
        assertThat(response.headers().firstValue("Content-Type").orElseThrow())
                .startsWith("application/problem+json");
        assertThat(JsonPath.<String>read(response.body(), "$.code"))
                .isEqualTo("RATE_LIMIT_EXCEEDED");
        assertThat(JsonPath.<String>read(response.body(), "$.title"))
                .isEqualTo("Request quota exceeded");
    }
}
