package com.conexa.starwars.ratelimit;

public final class QuotaExceededException extends RuntimeException {
    private final long retryAfterSeconds;

    QuotaExceededException(long retryAfterSeconds) {
        super("Request quota exceeded");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
