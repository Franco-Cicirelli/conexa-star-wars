package com.conexa.starwars.catalog.application;

import java.util.List;

public record CatalogPage(
        List<CatalogItem> items, int page, int size, long totalItems, int totalPages) {
    public CatalogPage {
        items = List.copyOf(items);
    }

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
