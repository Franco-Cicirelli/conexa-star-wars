package com.conexa.starwars.catalog.application;

import java.util.List;
import java.util.Optional;

/**
 * Provider-independent catalog port. Absence is data; an unavailable or incompatible provider is a
 * {@link CatalogFailure}, never an empty result fabricated from an error.
 */
public interface CatalogGateway {
    /**
     * Returns one provider page for resources with upstream pagination, preserving source order.
     */
    CatalogPage list(CatalogResource resource, int page, int size);

    /** Returns an item or verified UID absence; the caller supplies a normalized positive UID. */
    Optional<CatalogItem> detail(CatalogResource resource, String id);

    /**
     * Returns the complete validated Films collection, before request-specific filtering/sorting.
     */
    List<CatalogItem> films();

    /**
     * Returns complete search candidates. A literal-search fallback may return a superset of
     * matches; the application applies its own case-insensitive substring filter before paging.
     */
    List<CatalogItem> search(CatalogResource resource, String name);
}
