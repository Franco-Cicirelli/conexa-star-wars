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

    List<CatalogItem> get(Supplier<List<CatalogItem>> loader) {
        return cache.get(KEY, ignored -> loader.get());
    }

    public void clear() {
        cache.invalidateAll();
    }
}
