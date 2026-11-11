package com.conexa.starwars.ratelimit;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** Bounded registry that never evicts an active identity to admit a new one. */
final class IdentityQuota {
    private final Map<String, Entry> entries = new HashMap<>();
    private final RateLimiterConfig configuration;
    private final int maximumIdentities;
    private final LongSupplier ticker;
    private long lastCleanup;

    IdentityQuota(int permits, Duration period, int maximumIdentities, LongSupplier ticker) {
        if (period.isNegative() || period.isZero() || maximumIdentities < 1) {
            throw new IllegalArgumentException(
                    "Quota period and identity capacity must be positive.");
        }
        this.configuration =
                RateLimiterConfig.custom()
                        .limitForPeriod(permits)
                        .limitRefreshPeriod(period)
                        .timeoutDuration(Duration.ZERO)
                        .build();
        this.maximumIdentities = maximumIdentities;
        this.ticker = ticker;
        this.lastCleanup = ticker.getAsLong();
    }

    synchronized void acquire(String identity) {
        long now = ticker.getAsLong();
        long period = configuration.getLimitRefreshPeriod().toNanos();
        if (now - lastCleanup >= period || entries.size() >= maximumIdentities) {
            entries.values().removeIf(entry -> now - entry.lastSeen >= period);
            lastCleanup = now;
        }
        Entry entry = entries.get(identity);
        if (entry == null) {
            if (entries.size() >= maximumIdentities) throw exceeded();
            entry = new Entry(RateLimiter.of("request-quota", configuration), now);
            entries.put(identity, entry);
        }
        entry.lastSeen = now;
        if (!entry.limiter.acquirePermission()) throw exceeded();
    }

    private QuotaExceededException exceeded() {
        // A full refresh period is a conservative retry bound, including registry saturation.
        long nanos = configuration.getLimitRefreshPeriod().toNanos();
        return new QuotaExceededException(Math.max(1, (nanos - 1) / 1_000_000_000L + 1));
    }

    synchronized void clear() {
        entries.clear();
    }

    private static final class Entry {
        private final RateLimiter limiter;
        private long lastSeen;

        private Entry(RateLimiter limiter, long lastSeen) {
            this.limiter = limiter;
            this.lastSeen = lastSeen;
        }
    }
}
