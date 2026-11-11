package com.conexa.starwars.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class LoginServiceTest {
    private final AuthenticationManager authenticationManager = mock(AuthenticationManager.class);
    private final AccessTokenIssuer tokens = mock(AccessTokenIssuer.class);
    private final LoginService service = new LoginService(authenticationManager, tokens);
    private final String password = UUID.randomUUID().toString();

    @Test
    void failedAuthenticationCannotIssueAToken() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Invalid credentials"));
        assertThatThrownBy(() -> service.login("reader", password))
                .isInstanceOf(BadCredentialsException.class);
        verifyNoInteractions(tokens);
    }

    @Test
    void scopesComeFromTheAuthenticatedPrincipal() {
        var principal =
                UsernamePasswordAuthenticationToken.authenticated(
                        "reader",
                        null,
                        List.of(
                                new SimpleGrantedAuthority("profile:read"),
                                mock(
                                        org.springframework.security.core.authority
                                                .FactorGrantedAuthority.class),
                                new SimpleGrantedAuthority("catalog:read")));
        when(authenticationManager.authenticate(any())).thenReturn(principal);
        var expectedUser = new AuthenticatedUser("reader", "catalog:read profile:read");
        var expectedResponse = new IssuedToken("generated-in-test", "Bearer", 900);
        when(tokens.issue(expectedUser)).thenReturn(expectedResponse);
        assertThat(service.login("reader", password)).isSameAs(expectedResponse);
        verify(authenticationManager)
                .authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated("reader", password));
    }
}
