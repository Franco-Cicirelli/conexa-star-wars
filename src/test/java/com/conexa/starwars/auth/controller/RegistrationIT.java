package com.conexa.starwars.auth.controller;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

@org.springframework.test.annotation.DirtiesContext(
        classMode =
                org.springframework.test.annotation.DirtiesContext.ClassMode
                        .BEFORE_EACH_TEST_METHOD)
class RegistrationIT extends CatalogIntegrationSupport {
    @Autowired private InMemoryUserDetailsManager users;
    @Autowired private PasswordEncoder passwords;
    @Autowired private JwtDecoder decoder;

    @Test
    void publicRegistrationLoginAndProtectedCatalogWorkEndToEnd() throws Exception {
        String username = "registered-" + UUID.randomUUID();
        String password = " " + UUID.randomUUID() + " ";
        var response = post("register", Map.of("username", username, "password", password));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(JsonPath.<String>read(response.body(), "$.username")).isEqualTo(username);
        assertThat(response.body()).doesNotContain(password, "password", "accessToken");
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        var user = users.loadUserByUsername(username);
        assertThat(user.getPassword()).startsWith("$argon2id$");
        assertThat(passwords.matches(password, user.getPassword())).isTrue();
        assertThat(user.getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("catalog:read");
        assertThat(swapi.getAllServeEvents()).isEmpty();

        var login = post("login", Map.of("username", username, "password", password));
        assertThat(login.statusCode()).isEqualTo(200);
        String token = JsonPath.read(login.body(), "$.accessToken");
        assertThat(decoder.decode(token).getClaimAsString("scope")).isEqualTo("catalog:read");
        swapi.stubFor(
                get(urlEqualTo("/api/people?page=1&limit=10"))
                        .willReturn(okJson(fixture("people-size-10"))));
        assertThat(rawGet("/api/v1/people", token).statusCode()).isEqualTo(200);
        assertThat(swapi.getAllServeEvents()).hasSize(1);
    }

    @Test
    void duplicateIsCaseInsensitiveAndCannotReplacePassword() throws Exception {
        String username = "duplicate-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        String different = UUID.randomUUID().toString();
        assertThat(
                        post("register", Map.of("username", username, "password", password))
                                .statusCode())
                .isEqualTo(201);
        var duplicate =
                post(
                        "register",
                        Map.of(
                                "username",
                                username.toUpperCase(java.util.Locale.ROOT),
                                "password",
                                different));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(JsonPath.<String>read(duplicate.body(), "$.code"))
                .isEqualTo("AUTH_USERNAME_EXISTS");
        assertThat(passwords.matches(password, users.loadUserByUsername(username).getPassword()))
                .isTrue();
        assertThat(passwords.matches(different, users.loadUserByUsername(username).getPassword()))
                .isFalse();
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void requestCannotChooseAuthorities() throws Exception {
        String username = "scopes-" + UUID.randomUUID();
        var response =
                post(
                        "register",
                        Map.of(
                                "username",
                                username,
                                "password",
                                UUID.randomUUID().toString(),
                                "scope",
                                "admin:write",
                                "roles",
                                java.util.List.of("ADMIN")));
        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(users.loadUserByUsername(username).getAuthorities())
                .extracting(org.springframework.security.core.GrantedAuthority::getAuthority)
                .containsExactly("catalog:read");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{}",
                "{\"username\":\"valid\"}",
                "{\"password\":\"not-a-real-secret\"}",
                "{\"username\":\"\",\"password\":\"not-a-real-secret\"}",
                "{\"username\":\"bad user\",\"password\":\"not-a-real-secret\"}",
                "{\"username\":\"valid\",\"password\":\"short\"}",
                "{\"username\":\"valid\",\"password\":\"            \"}",
                "malformed"
            })
    void invalidRegistrationIs400WithoutCallingSwapi(String body) throws Exception {
        var response = postJson("register", body);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(JsonPath.<String>read(response.body(), "$.code"))
                .isEqualTo("AUTH_INVALID_REGISTRATION");
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void rejectsOversizedInputs() throws Exception {
        assertThat(
                        post(
                                        "register",
                                        Map.of(
                                                "username",
                                                "a".repeat(81),
                                                "password",
                                                UUID.randomUUID().toString()))
                                .statusCode())
                .isEqualTo(400);
        assertThat(
                        post("register", Map.of("username", "valid", "password", "x".repeat(257)))
                                .statusCode())
                .isEqualTo(400);
    }

    @Test
    void sameIpCannotRegisterTwiceEvenWithSpoofedForwardedHeader() throws Exception {
        String first = "first-" + UUID.randomUUID();
        assertThat(
                        post(
                                        "register",
                                        Map.of(
                                                "username",
                                                first,
                                                "password",
                                                UUID.randomUUID().toString()))
                                .statusCode())
                .isEqualTo(201);
        String second = "second-" + UUID.randomUUID();
        String body =
                new tools.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                                Map.of(
                                        "username",
                                        second,
                                        "password",
                                        UUID.randomUUID().toString()));
        try (var client = HttpClient.newHttpClient()) {
            var response =
                    client.send(
                            HttpRequest.newBuilder(
                                            URI.create(
                                                    "http://localhost:"
                                                            + port
                                                            + "/api/v1/auth/register"))
                                    .header("Content-Type", "application/json")
                                    .header("X-Forwarded-For", "198.51.100.99")
                                    .header("Forwarded", "for=198.51.100.99")
                                    .POST(HttpRequest.BodyPublishers.ofString(body))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(409);
            assertThat(JsonPath.<String>read(response.body(), "$.code"))
                    .isEqualTo("AUTH_REGISTRATION_IP_USED");
            assertThat(users.userExists(second)).isFalse();
            assertThat(swapi.getAllServeEvents()).isEmpty();
        }
    }

    @Test
    void invalidAttemptDoesNotConsumeIpRegistration() throws Exception {
        assertThat(post("register", Map.of("username", "valid", "password", "short")).statusCode())
                .isEqualTo(400);
        assertThat(
                        post(
                                        "register",
                                        Map.of(
                                                "username",
                                                "valid",
                                                "password",
                                                UUID.randomUUID().toString()))
                                .statusCode())
                .isEqualTo(201);
    }

    private HttpResponse<String> post(String route, Map<String, ?> body) throws Exception {
        return postJson(route, new tools.jackson.databind.ObjectMapper().writeValueAsString(body));
    }

    private HttpResponse<String> postJson(String route, String body) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(
                    HttpRequest.newBuilder(
                                    URI.create(
                                            "http://localhost:" + port + "/api/v1/auth/" + route))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
        }
    }
}
