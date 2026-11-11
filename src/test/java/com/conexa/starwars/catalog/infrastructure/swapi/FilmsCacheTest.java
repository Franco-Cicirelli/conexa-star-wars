package com.conexa.starwars.catalog.infrastructure.swapi;

import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.catalog.application.CatalogItem;
import com.github.benmanes.caffeine.cache.Ticker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FilmsCacheTest {
    private final CatalogItem film = new CatalogItem("1", "A New Hope");

    @Test
    void equivalentRequestsShareOneLoadUntilExpiry() {
        var nanos = new AtomicLong();
        var ticker = (Ticker) () -> nanos.get();
        var meters = new SimpleMeterRegistry();
        var cache = new FilmsCache(meters, ticker);
        var loads = new AtomicInteger();

        assertThat(cache.get(() -> loaded(loads))).containsExactly(film);
        assertThat(cache.get(() -> loaded(loads))).containsExactly(film);
        assertThat(loads).hasValue(1);

        nanos.set(Duration.ofMinutes(5).toNanos());
        assertThat(cache.get(() -> loaded(loads))).containsExactly(film);
        assertThat(loads).hasValue(2);
        assertThat(meters.get("cache.gets").functionCounter().count()).isPositive();
    }

    @Test
    void concurrentRequestsShareOneInFlightLoad() throws Exception {
        var ticker = Ticker.systemTicker();
        var cache = new FilmsCache(new SimpleMeterRegistry(), ticker);
        var loads = new AtomicInteger();
        var start = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            var first =
                    executor.submit(
                            () ->
                                    cache.get(
                                            () -> {
                                                loads.incrementAndGet();
                                                start.countDown();
                                                await(release);
                                                return List.of(film);
                                            }));
            start.await();
            var second = executor.submit(() -> cache.get(() -> loaded(loads)));
            release.countDown();
            assertThat(first.get()).containsExactly(film);
            assertThat(second.get()).containsExactly(film);
        }
        assertThat(loads).hasValue(1);
    }

    private List<CatalogItem> loaded(AtomicInteger loads) {
        loads.incrementAndGet();
        return List.of(film);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while loading Films", exception);
        }
    }
}
