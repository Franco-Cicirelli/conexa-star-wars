package com.conexa.starwars.auth.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthRateLimiterTest {
    @Test
    void limitsAttemptsPerAddress() {
        var limiter = new AuthRateLimiter();
        for (int attempt = 0; attempt < 10; attempt++)
            assertThat(limiter.allow("client-a")).isTrue();
        assertThat(limiter.allow("client-a")).isFalse();
        assertThat(limiter.allow("client-b")).isTrue();
    }

    @Test
    void expiredWindowAllowsNewAttemptsWithoutSleeping() {
        var ticker = new java.util.concurrent.atomic.AtomicLong();
        var limiter = new AuthRateLimiter(ticker::get);
        for (int attempt = 0; attempt < 10; attempt++) limiter.allow("client");
        org.assertj.core.api.Assertions.assertThat(limiter.allow("client")).isFalse();
        ticker.set(java.time.Duration.ofMinutes(1).toNanos());
        org.assertj.core.api.Assertions.assertThat(limiter.allow("client")).isTrue();
    }
}
