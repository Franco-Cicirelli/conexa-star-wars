package com.conexa.starwars.catalog.infrastructure.swapi;

import com.conexa.starwars.catalog.application.CatalogGateway;
import com.conexa.starwars.catalog.application.CatalogItem;
import com.conexa.starwars.catalog.application.CatalogPage;
import com.conexa.starwars.catalog.application.CatalogResource;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

@Component
public class SwapiCatalogAdapter implements CatalogGateway {
    private static final String REGEX_METACHARACTERS = "\\.^$|?*+()[]{}";
    private final SwapiClient client;
    private final SwapiResponseMapper mapper;
    private final CompleteResourceReader completeReader;
    private final FilmsCache filmsCache;

    public SwapiCatalogAdapter(
            SwapiClient client,
            SwapiResponseMapper mapper,
            CompleteResourceReader completeReader,
            FilmsCache filmsCache) {
        this.client = client;
        this.mapper = mapper;
        this.completeReader = completeReader;
        this.filmsCache = filmsCache;
    }

    @Override
    public CatalogPage list(CatalogResource resource, int page, int size) {
        return mapper.page(client.page(resource, page, size), page, size);
    }

    @Override
    public Optional<CatalogItem> detail(CatalogResource resource, String id) {
        return client.detail(resource, id).map(item -> mapper.detail(resource, item));
    }

    @Override
    public List<CatalogItem> films() {
        return filmsCache.get(() -> mapper.collection(CatalogResource.FILMS, client.films()));
    }

    @Override
    public List<CatalogItem> search(CatalogResource resource, String name) {
        boolean requiresLiteralSearch =
                name.chars().anyMatch(character -> REGEX_METACHARACTERS.indexOf(character) >= 0);
        if (requiresLiteralSearch) {
            return completeReader.read(resource);
        }
        return mapper.collection(resource, client.search(resource, name));
    }
}
