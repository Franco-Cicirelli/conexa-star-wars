package com.conexa.starwars.auth.jwt;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

@Configuration
public class JwtConfiguration {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RSAKey signingKey(
            @Value("${auth.jwt.private-key-b64}") String privateKey,
            @Value("${auth.jwt.public-key-b64}") String publicKey)
            throws Exception {
        var factory = KeyFactory.getInstance("RSA");
        var secret =
                (RSAPrivateKey)
                        factory.generatePrivate(
                                new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
        var shared =
                (RSAPublicKey)
                        factory.generatePublic(
                                new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
        if (shared.getModulus().bitLength() < 2048
                || !shared.getModulus().equals(secret.getModulus())) {
            throw new IllegalArgumentException(
                    "A matching RSA key pair of at least 2048 bits is required.");
        }
        return new RSAKey.Builder(shared).privateKey(secret).keyID("catalog-signing-key").build();
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(
            RSAKey key,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.audience}") String audience,
            Clock clock)
            throws Exception {
        var decoder =
                NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey())
                        .signatureAlgorithm(SignatureAlgorithm.RS256)
                        .build();
        OAuth2TokenValidator<Jwt> audienceValidator =
                jwt ->
                        jwt.getAudience().contains(audience)
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error("invalid_token", "Invalid audience", null));
        var timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> requiredClaims =
                jwt ->
                        jwt.getSubject() != null
                                        && !jwt.getSubject().isBlank()
                                        && jwt.getIssuedAt() != null
                                        && jwt.getExpiresAt() != null
                                        && !jwt.getIssuedAt().isAfter(clock.instant())
                                ? OAuth2TokenValidatorResult.success()
                                : OAuth2TokenValidatorResult.failure(
                                        new OAuth2Error(
                                                "invalid_token",
                                                "Missing or invalid required claims",
                                                null));
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamps,
                        new JwtIssuerValidator(issuer),
                        audienceValidator,
                        requiredClaims));
        return decoder;
    }
}
