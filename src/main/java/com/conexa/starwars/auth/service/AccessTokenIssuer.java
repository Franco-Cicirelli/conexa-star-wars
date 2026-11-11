package com.conexa.starwars.auth.service;

public interface AccessTokenIssuer {
    IssuedToken issue(AuthenticatedUser user);
}
