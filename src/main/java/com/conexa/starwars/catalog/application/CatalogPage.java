package com.conexa.starwars.catalog.application;

import java.util.List;

/** A public page with defensively copied items and totals calculated before pagination. */
public record CatalogPage(
        List<CatalogItem> items, int page, int size, long totalItems, int totalPages) {
    public CatalogPage {
        items = List.copyOf(items);
    }

    /**
     * Slices a complete, already filtered and ordered collection. Long offsets prevent integer
     * overflow for large page numbers. Requests beyond the total return empty items with the
     * original totals; this method does not clamp to the last page or change the supplied order.
     *
     * @param matches all matches, not an upstream partial page
     * @param page one-based page number
     * @param size page size in the public range 1 through 10
     * @return immutable public page
     * @throws IllegalArgumentException if page or size is outside the supported range
     */
    public static CatalogPage slice(List<CatalogItem> matches, int page, int size) {
        if (page < 1 || size < 1 || size > 10) {
            throw new IllegalArgumentException(
                    "Page and size must be within the catalog's supported range.");
        }
        long offset = ((long) page - 1) * size;
        int total = matches.size();
        var items =
                offset >= total
                        ? List.<CatalogItem>of()
                        : matches.subList((int) offset, (int) Math.min(offset + size, total));
        int totalPages = (int) ((total + (long) size - 1) / size);
        return new CatalogPage(items, page, size, total, totalPages);
    }
}
