package com.conexa.starwars.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CatalogPageTest {
    private final List<CatalogItem> items =
            List.of(
                    new CatalogItem("1", "Luke"),
                    new CatalogItem("11", "Anakin"),
                    new CatalogItem("43", "Shmi"));

    @Test
    void slicesAfterComputingTotals() {
        var page = CatalogPage.slice(items, 2, 1);
        assertThat(page.items()).containsExactly(items.get(1));
        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    void largeOffsetsRemainEmptyWithoutOverflowOrLosingTotals() {
        var page = CatalogPage.slice(items, Integer.MAX_VALUE, 10);
        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(1);
    }

    @Test
    void emptyMatchesHaveNoPages() {
        assertThat(CatalogPage.slice(List.of(), 1, 10).totalPages()).isZero();
    }

    @Test
    void pageOwnsAnImmutableSnapshot() {
        var source = new ArrayList<>(items);
        var page = CatalogPage.slice(source, 1, 2);
        source.clear();
        assertThat(page.items()).hasSize(2);
        assertThatThrownBy(() -> page.items().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
