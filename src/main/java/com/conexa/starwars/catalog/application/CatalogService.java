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
