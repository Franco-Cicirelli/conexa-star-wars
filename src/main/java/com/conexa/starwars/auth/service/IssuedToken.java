package com.conexa.starwars.auth.service;

public record IssuedToken(String accessToken, String tokenType, long expiresIn) {
    @Override
    public String toString() {
        return "IssuedToken[redacted]";
    }
}
