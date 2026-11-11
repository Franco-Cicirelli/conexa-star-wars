package com.conexa.starwars.catalog.application;

public class CatalogFailure extends RuntimeException {
    public enum Reason {
        INVALID_RESPONSE,
        UNAVAILABLE,
        TIMEOUT
    }

    private final Reason reason;

    public CatalogFailure(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public static CatalogFailure invalidResponse() {
        return new CatalogFailure(Reason.INVALID_RESPONSE, null);
    }

    public Reason reason() {
        return reason;
    }
}
