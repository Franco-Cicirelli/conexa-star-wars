package com.conexa.starwars.catalog.infrastructure.swapi;

import static com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.*;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.conexa.starwars.catalog.application.CatalogFailure;
import com.conexa.starwars.catalog.application.CatalogResource;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CompleteResourceReaderTest {
    private final SwapiClient client = mock(SwapiClient.class);
    private final AtomicLong ticker = new AtomicLong();
    private final CompleteResourceReader reader =
            new CompleteResourceReader(client, new SwapiResponseMapper(), ticker::get);

    @Test
    void deadlineRejectsResultsWithoutSleeping() {
        when(client.page(CatalogResource.PEOPLE, 1, 10))
                .thenAnswer(
                        call -> {
                            ticker.set(Duration.ofSeconds(31).toNanos());
                            return new PageEnvelope("ok", List.of(), 0L, 0);
                        });
        assertFailure(CatalogFailure.Reason.TIMEOUT);
        verify(client).page(CatalogResource.PEOPLE, 1, 10);
        verifyNoMoreInteractions(client);
    }

    @Test
    void changingMetadataCannotProducePartialTotals() {
        when(client.page(CatalogResource.PEOPLE, 1, 10)).thenReturn(page(11, 2));
        when(client.page(CatalogResource.PEOPLE, 2, 10)).thenReturn(page(12, 2));
        assertFailure(CatalogFailure.Reason.INVALID_RESPONSE);
    }

    @Test
    void duplicateIdsAcrossPagesInvalidateTheSnapshot() {
        when(client.page(CatalogResource.PEOPLE, 1, 10)).thenReturn(page(11, 2));
        when(client.page(CatalogResource.PEOPLE, 2, 10))
                .thenReturn(
                        new PageEnvelope("ok", List.of(new SwapiItem("1", "Repeated")), 11L, 2));
        assertFailure(CatalogFailure.Reason.INVALID_RESPONSE);
    }

    private PageEnvelope page(long total, int pages) {
        return new PageEnvelope(
                "ok",
                IntStream.rangeClosed(1, 10)
                        .mapToObj(id -> new SwapiItem(Integer.toString(id), "Person " + id))
                        .toList(),
                total,
                pages);
    }

    private void assertFailure(CatalogFailure.Reason reason) {
        assertThatThrownBy(() -> reader.read(CatalogResource.PEOPLE))
                .isInstanceOfSatisfying(
                        CatalogFailure.class,
                        error ->
                                org.assertj.core.api.Assertions.assertThat(error.reason())
                                        .isEqualTo(reason));
    }
}
