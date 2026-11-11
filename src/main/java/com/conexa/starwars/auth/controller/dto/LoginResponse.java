package com.conexa.starwars.auth.controller.dto;

import com.conexa.starwars.auth.service.IssuedToken;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
    public static LoginResponse from(IssuedToken token) {
        return new LoginResponse(token.accessToken(), token.tokenType(), token.expiresIn());
    }

    @Override
    public String toString() {
        return "LoginResponse[redacted]";
    }
}
