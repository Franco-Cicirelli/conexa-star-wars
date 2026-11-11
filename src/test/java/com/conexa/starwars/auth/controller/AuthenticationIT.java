package com.conexa.starwars.auth.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AuthenticationIT extends CatalogIntegrationSupport {
    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "starships", "vehicles"})
    void missingTokenIs401WithoutCallingSwapiEvenForInvalidInput(String resource) throws Exception {
        var response = rawGet("/api/v1/" + resource + "?page=0", null);
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "starships", "vehicles"})
    void validTokenWithoutScopeIs403WithoutCallingSwapi(String resource) throws Exception {
        var response =
                rawGet(
                        "/api/v1/" + resource,
                        token(
                                "profile:read",
                                "test-issuer",
                                "test-audience",
                                java.time.Instant.now().plusSeconds(900)));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"people", "films", "starships", "vehicles"})
    void invalidTokensAre401WithoutCallingSwapi(String resource) throws Exception {
        for (String invalid :
                List.of(
                        "invalid.jwt.token",
                        foreignSignatureToken(),
                        token(
                                "catalog:read",
                                "test-issuer",
                                "test-audience",
                                java.time.Instant.now().minusSeconds(120)),
                        token(
                                "catalog:read",
                                "wrong-issuer",
                                "test-audience",
                                java.time.Instant.now().plusSeconds(900)),
                        token(
                                "catalog:read",
                                "test-issuer",
                                "wrong-audience",
                                java.time.Instant.now().plusSeconds(900)))) {
            assertThat(rawGet("/api/v1/" + resource, invalid).statusCode()).isEqualTo(401);
        }
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.provisioning.InMemoryUserDetailsManager users;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.crypto.password.PasswordEncoder passwordEncoder;

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.security.oauth2.jwt.JwtDecoder decoder;

    @Test
    void loginAuthenticatesInMemoryAndIssuesRs256TokenThatCanReadCatalog() throws Exception {
        var account = users.loadUserByUsername(USERNAME);
        assertThat(account.getPassword()).startsWith("$argon2id$");
        assertThat(passwordEncoder.matches(PASSWORD, account.getPassword())).isTrue();
        var response = login(USERNAME, PASSWORD);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("Set-Cookie")).isEmpty();
        String token = JsonPath.read(response.body(), "$.accessToken");
        assertThat(JsonPath.<String>read(response.body(), "$.tokenType")).isEqualTo("Bearer");
        assertThat(JsonPath.<Integer>read(response.body(), "$.expiresIn")).isEqualTo(900);
        var jwt = decoder.decode(token);
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getSubject()).isEqualTo(USERNAME);
        assertThat(jwt.getClaimAsString("scope")).isEqualTo("catalog:read");
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds())
                .isEqualTo(900);
        swapi.stubFor(
                get(urlEqualTo("/api/people?page=1&limit=10"))
                        .willReturn(okJson(fixture("people-size-10"))));
        assertThat(rawGet("/api/v1/people", token).statusCode()).isEqualTo(200);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
        // A previous authenticated request must not create a session usable without Bearer.
        assertThat(rawGet("/api/v1/people", null).statusCode()).isEqualTo(401);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void loginUsesOnlyConfiguredUserAuthorities() throws Exception {
        String reader = "reader-" + java.util.UUID.randomUUID();
        users.createUser(
                org.springframework.security.core.userdetails.User.withUsername(reader)
                        .password(passwordEncoder.encode(PASSWORD))
                        .authorities("profile:read")
                        .build());
        try {
            var response = login(reader, PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            String token = JsonPath.read(response.body(), "$.accessToken");
            assertThat(decoder.decode(token).getClaimAsString("scope")).isEqualTo("profile:read");
            for (String resource : List.of("people", "films", "starships", "vehicles")) {
                assertThat(rawGet("/api/v1/" + resource, token).statusCode()).isEqualTo(403);
            }
            assertThat(swapi.getAllServeEvents()).isEmpty();
        } finally {
            users.deleteUser(reader);
        }
    }

    @Test
    void wrongPasswordAndUnknownUserHaveSameGenericError() throws Exception {
        var wrong = login(USERNAME, "incorrect-password");
        var unknown = login("unknown-" + java.util.UUID.randomUUID(), "incorrect-password");
        assertThat(wrong.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(JsonPath.<String>read(wrong.body(), "$.detail"))
                .isEqualTo(JsonPath.read(unknown.body(), "$.detail"));
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void changingPasswordDoesNotRevokePreviouslyIssuedJwt() throws Exception {
        String username = "temporary-" + java.util.UUID.randomUUID();
        var originalHash = passwordEncoder.encode(PASSWORD);
        users.createUser(
                org.springframework.security.core.userdetails.User.withUsername(username)
                        .password(originalHash)
                        .authorities("catalog:read")
                        .build());
        try {
            var response = login(username, PASSWORD);
            assertThat(response.statusCode()).isEqualTo(200);
            String token = JsonPath.read(response.body(), "$.accessToken");
            users.updateUser(
                    org.springframework.security.core.userdetails.User.withUsername(username)
                            .password(
                                    passwordEncoder.encode(java.util.UUID.randomUUID().toString()))
                            .authorities("catalog:read")
                            .build());
            assertThat(login(username, PASSWORD).statusCode()).isEqualTo(401);
            swapi.stubFor(
                    get(urlEqualTo("/api/people?page=1&limit=10"))
                            .willReturn(okJson(fixture("people-size-10"))));
            assertThat(rawGet("/api/v1/people", token).statusCode()).isEqualTo(200);
            assertThat(swapi.getAllServeEvents()).hasSize(1);
        } finally {
            users.deleteUser(username);
        }
    }

    @Test
    void missingExpirationAndWrongSigningAlgorithmAreRejectedBeforeSwapi() throws Exception {
        var now = java.time.Instant.now();
        var withoutExpiration =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .subject(USERNAME)
                        .issuer("test-issuer")
                        .audience(List.of("test-audience"))
                        .issuedAt(now)
                        .claim("scope", "catalog:read")
                        .build();
        var validClaims =
                org.springframework.security.oauth2.jwt.JwtClaimsSet.builder()
                        .subject(USERNAME)
                        .issuer("test-issuer")
                        .audience(List.of("test-audience"))
                        .issuedAt(now)
                        .expiresAt(now.plusSeconds(900))
                        .claim("scope", "catalog:read")
                        .build();
        for (var parameters :
                List.of(
                        org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                                org.springframework.security.oauth2.jwt.JwsHeader.with(
                                                org.springframework.security.oauth2.jose.jws
                                                        .SignatureAlgorithm.RS256)
                                        .build(),
                                withoutExpiration),
                        org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                                org.springframework.security.oauth2.jwt.JwsHeader.with(
                                                org.springframework.security.oauth2.jose.jws
                                                        .SignatureAlgorithm.RS512)
                                        .build(),
                                validClaims))) {
            String invalid = encoder.encode(parameters).getTokenValue();
            assertThat(rawGet("/api/v1/people", invalid).statusCode()).isEqualTo(401);
        }
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    private HttpResponse<String> login(String username, String password) throws Exception {
        // Credentials here are generated only in the test environment.
        var body =
                new tools.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                java.util.Map.of("username", username, "password", password));
        try (var client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(
                                    URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
