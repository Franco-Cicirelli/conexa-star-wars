package com.conexa.starwars.catalog.application;

import java.math.BigInteger;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class CatalogService {
    private final CatalogGateway gateway;

    public CatalogService(CatalogGateway gateway) {
        this.gateway = gateway;
    }

    /**
     * Selects UID detail first, then Films collection/search, otherwise an upstream page. The HTTP
     * layer validates page/size/UID and trims name before calling this method. Local totals
     * describe all matches before slicing; combining ID and name requires both to match. Security
     * and quota acquisition belong to the request boundary, not this provider-independent use case.
     *
     * @param resource allowed catalog resource
     * @param page one-based requested page
     * @param size requested page size, from 1 to 10
     * @param id optional positive numeric UID
     * @param name optional trimmed substring
     * @return provider page or locally sliced matches, preserving the applicable ordering contract
     * @throws CatalogFailure when provider work fails or cannot yield a complete valid result
     */
    public CatalogPage find(CatalogResource resource, int page, int size, String id, String name) {
        if (id == null && name == null && resource != CatalogResource.FILMS) {
            return gateway.list(resource, page, size);
        }
        var candidates = findCandidates(resource, id, name);
        String normalizedName = name == null ? null : name.toLowerCase(Locale.ROOT);
        var matches =
                candidates.stream()
                        .filter(
                                item ->
                                        normalizedName == null
                                                || item.name()
                                                        .toLowerCase(Locale.ROOT)
                                                        .contains(normalizedName))
                        .toList();
        return CatalogPage.slice(matches, page, size);
    }

    private List<CatalogItem> findCandidates(CatalogResource resource, String id, String name) {
        if (id != null) {
            return gateway.detail(resource, new BigInteger(id).toString()).stream().toList();
        }
        if (resource == CatalogResource.FILMS) {
            return gateway.films().stream()
                    .sorted(Comparator.comparing(item -> new BigInteger(item.id())))
                    .toList();
        }
        return gateway.search(resource, name);
    }
}
