package com.conexa.starwars.auth.service;

import java.util.stream.Collectors;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.stereotype.Service;

@Service
public class LoginService {
    private final AuthenticationManager authenticationManager;
    private final AccessTokenIssuer tokens;

    public LoginService(AuthenticationManager authenticationManager, AccessTokenIssuer tokens) {
        this.authenticationManager = authenticationManager;
        this.tokens = tokens;
    }

    public IssuedToken login(String username, String password) {
        var authenticated =
                authenticationManager.authenticate(
                        UsernamePasswordAuthenticationToken.unauthenticated(username, password));
        String scope =
                authenticated.getAuthorities().stream()
                        .filter(authority -> !(authority instanceof FactorGrantedAuthority))
                        .map(GrantedAuthority::getAuthority)
                        .sorted()
                        .collect(Collectors.joining(" "));
        return tokens.issue(new AuthenticatedUser(authenticated.getName(), scope));
    }
}
