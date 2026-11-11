package com.conexa.starwars.catalog.application;

import java.util.List;
import java.util.Optional;

public interface CatalogGateway {
    CatalogPage list(CatalogResource resource, int page, int size);

    Optional<CatalogItem> detail(CatalogResource resource, String id);

    List<CatalogItem> films();

    List<CatalogItem> search(CatalogResource resource, String name);
}
