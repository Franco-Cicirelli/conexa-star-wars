package com.conexa.starwars.auth.jwt;

import com.conexa.starwars.auth.service.AccessTokenIssuer;
import com.conexa.starwars.auth.service.AuthenticatedUser;
import com.conexa.starwars.auth.service.IssuedToken;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {
    private static final Duration TOKEN_LIFETIME = Duration.ofMinutes(15);
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;

    public JwtAccessTokenIssuer(
            JwtEncoder encoder,
            Clock clock,
            @Value("${auth.jwt.issuer}") String issuer,
            @Value("${auth.jwt.audience}") String audience) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = issuer;
        this.audience = audience;
    }

    @Override
    public IssuedToken issue(AuthenticatedUser user) {
        var now = clock.instant();
        var claims =
                JwtClaimsSet.builder()
                        .issuer(issuer)
                        .audience(List.of(audience))
                        .subject(user.username())
                        .issuedAt(now)
                        .expiresAt(now.plus(TOKEN_LIFETIME))
                        .claim("scope", user.scope())
                        .build();
        var parameters =
                JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).build(), claims);
        var token = encoder.encode(parameters);
        return new IssuedToken(token.getTokenValue(), "Bearer", TOKEN_LIFETIME.toSeconds());
    }
}
