package com.conexa.starwars.support;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class IntegrationSupport {
    public static final String USERNAME = "test-" + UUID.randomUUID();
    public static final String PASSWORD = UUID.randomUUID().toString();
    private static final KeyPair KEYS = keys();
    @Autowired protected JwtEncoder encoder;

    @DynamicPropertySource
    static void securityProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "auth.jwt.private-key-b64",
                () -> Base64.getEncoder().encodeToString(KEYS.getPrivate().getEncoded()));
        registry.add(
                "auth.jwt.public-key-b64",
                () -> Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()));
        registry.add("auth.jwt.issuer", () -> "test-issuer");
        registry.add("auth.jwt.audience", () -> "test-audience");
    }

    @Autowired private org.springframework.security.provisioning.InMemoryUserDetailsManager users;
    @Autowired private com.conexa.starwars.auth.service.RegistrationService registrations;

    @org.junit.jupiter.api.BeforeEach
    void createTestAccount() {
        if (!users.userExists(USERNAME)) registrations.register(USERNAME, PASSWORD, "192.0.2.250");
    }

    protected String accessToken() {
        return token(
                "catalog:read", "test-issuer", "test-audience", Instant.now().plusSeconds(900));
    }

    protected String token(String scope, String issuer, String audience, Instant expires) {
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(USERNAME)
                        .issuedAt(expires.minusSeconds(900))
                        .expiresAt(expires)
                        .claim("scope", scope)
                        .build();
        return encoder.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }

    protected String foreignSignatureToken() {
        var pair = keys();
        var jwk =
                new com.nimbusds.jose.jwk.RSAKey.Builder((RSAPublicKey) pair.getPublic())
                        .privateKey((RSAPrivateKey) pair.getPrivate())
                        .build();
        var foreign =
                new NimbusJwtEncoder(
                        new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(
                                new com.nimbusds.jose.jwk.JWKSet(jwk)));
        var now = Instant.now();
        return foreign.encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(),
                                JwtClaimsSet.builder()
                                        .issuer("test-issuer")
                                        .audience(List.of("test-audience"))
                                        .subject(USERNAME)
                                        .issuedAt(now)
                                        .expiresAt(now.plusSeconds(900))
                                        .claim("scope", "catalog:read")
                                        .build()))
                .getTokenValue();
    }

    private static KeyPair keys() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
