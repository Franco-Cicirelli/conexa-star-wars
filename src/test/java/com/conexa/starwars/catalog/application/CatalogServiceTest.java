package com.conexa.starwars.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class CatalogServiceTest {
    private final CatalogGateway gateway = mock(CatalogGateway.class);
    private final CatalogService service = new CatalogService(gateway);

    @Test
    void filmsAreSortedByNumericUidRatherThanText() {
        when(gateway.films())
                .thenReturn(
                        List.of(
                                new CatalogItem("10", "Ten"),
                                new CatalogItem("2", "Two"),
                                new CatalogItem("1", "One")));
        assertThat(service.find(CatalogResource.FILMS, 1, 10, null, null).items())
                .extracting(CatalogItem::id)
                .containsExactly("1", "2", "10");
    }

    @Test
    void idHasPriorityAndNameFiltersBeforeComputingTotal() {
        when(gateway.detail(CatalogResource.FILMS, "1"))
                .thenReturn(Optional.of(new CatalogItem("1", "A New Hope")));
        var result = service.find(CatalogResource.FILMS, 1, 10, "001", "empire");
        assertThat(result.items()).isEmpty();
        assertThat(result.totalItems()).isZero();
        verify(gateway).detail(CatalogResource.FILMS, "1");
        verifyNoMoreInteractions(gateway);
    }

    @Test
    void literalCaseInsensitiveMatchingDoesNotInterpretRegex() {
        when(gateway.search(CatalogResource.PEOPLE, "[a]"))
                .thenReturn(
                        List.of(
                                new CatalogItem("1", "Pilot [A]"),
                                new CatalogItem("2", "Pilot B")));
        var result = service.find(CatalogResource.PEOPLE, 1, 10, null, "[a]");
        assertThat(result.items()).extracting(CatalogItem::id).containsExactly("1");
        assertThat(result.totalItems()).isEqualTo(1);
    }
}
