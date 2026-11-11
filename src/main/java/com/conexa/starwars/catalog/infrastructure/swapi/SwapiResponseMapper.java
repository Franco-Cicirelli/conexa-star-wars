package com.conexa.starwars.catalog.infrastructure.swapi;

import static com.conexa.starwars.catalog.application.CatalogFailure.invalidResponse;

import com.conexa.starwars.catalog.application.CatalogItem;
import com.conexa.starwars.catalog.application.CatalogPage;
import com.conexa.starwars.catalog.application.CatalogResource;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.CollectionEnvelope;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.DetailItem;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.PageEnvelope;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class SwapiResponseMapper {
    CatalogPage page(PageEnvelope response, int page, int size) {
        if (response == null
                || !"ok".equals(response.message())
                || response.results() == null
                || response.totalRecords() == null
                || response.totalRecords() < 0
                || response.totalPages() == null
                || response.totalPages() < 0) {
            throw invalidResponse();
        }
        var items =
                response.results().stream()
                        .map(
                                item -> {
                                    if (item == null) {
                                        throw invalidResponse();
                                    }
                                    return item(item.uid(), item.name());
                                })
                        .toList();
        return new CatalogPage(items, page, size, response.totalRecords(), response.totalPages());
    }

    List<CatalogItem> collection(CatalogResource resource, CollectionEnvelope response) {
        // Only a complete collection can produce trustworthy local totals.
        if (response == null
                || !"ok".equals(response.message())
                || response.result() == null
                || response.next() != null
                || response.totalPages() != null
                || response.totalRecords() != null) {
            throw invalidResponse();
        }
        return response.result().stream().map(item -> detail(resource, item)).toList();
    }

    CatalogItem detail(CatalogResource resource, DetailItem item) {
        if (item == null || item.properties() == null) {
            throw invalidResponse();
        }
        return item(
                item.uid(),
                resource == CatalogResource.FILMS
                        ? item.properties().title()
                        : item.properties().name());
    }

    private CatalogItem item(String uid, String name) {
        if (uid == null || !uid.matches("[1-9][0-9]*") || name == null || name.isBlank()) {
            throw invalidResponse();
        }
        return new CatalogItem(uid, name);
    }
}
