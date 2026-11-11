package com.conexa.starwars.catalog.infrastructure.swapi;

import com.conexa.starwars.catalog.application.CatalogItem;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.cache.CaffeineCacheMetrics;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Instance-local cache of one complete Films collection. Filtering, sorting, and pagination remain
 * outside the cache, so different requests reuse the same source data within the five-minute TTL.
 */
@Component
public final class FilmsCache {
    static final String KEY = "films:complete";

    private final Cache<String, List<CatalogItem>> cache;

    @Autowired
    public FilmsCache(MeterRegistry meterRegistry) {
        this(meterRegistry, Ticker.systemTicker());
    }

    FilmsCache(MeterRegistry meterRegistry, Ticker ticker) {
        this.cache =
                Caffeine.newBuilder()
                        .expireAfterWrite(Duration.ofMinutes(5))
                        .maximumSize(1)
                        .recordStats()
                        .ticker(ticker)
                        .build();
        CaffeineCacheMetrics.monitor(meterRegistry, cache, "catalog.films", "resource", "films");
    }

    /**
     * Coordinates one load for this key. The loader must return a complete validated immutable
     * collection; exceptions propagate and do not install a successful cache entry.
     */
    List<CatalogItem> get(Supplier<List<CatalogItem>> loader) {
        return cache.get(KEY, ignored -> loader.get());
    }

    public void clear() {
        cache.invalidateAll();
    }
}
