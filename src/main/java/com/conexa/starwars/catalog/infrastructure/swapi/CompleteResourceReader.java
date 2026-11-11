package com.conexa.starwars.catalog.infrastructure.swapi;

import static com.conexa.starwars.catalog.application.CatalogFailure.invalidResponse;

import com.conexa.starwars.catalog.application.CatalogFailure;
import com.conexa.starwars.catalog.application.CatalogItem;
import com.conexa.starwars.catalog.application.CatalogPage;
import com.conexa.starwars.catalog.application.CatalogResource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class CompleteResourceReader {
    private static final int PAGE_SIZE = 10;
    private static final int MAX_PAGES = 20;
    private static final long TIME_BUDGET_NANOS = Duration.ofSeconds(30).toNanos();
    private final SwapiClient client;
    private final SwapiResponseMapper mapper;
    private final LongSupplier ticker;

    @Autowired
    CompleteResourceReader(SwapiClient client, SwapiResponseMapper mapper) {
        this(client, mapper, System::nanoTime);
    }

    CompleteResourceReader(SwapiClient client, SwapiResponseMapper mapper, LongSupplier ticker) {
        this.client = client;
        this.mapper = mapper;
        this.ticker = ticker;
    }

    List<CatalogItem> read(CatalogResource resource) {
        long started = ticker.getAsLong();
        var first = readPage(resource, 1, started);
        validatePageCount(first);
        var items = new ArrayList<CatalogItem>();
        var ids = new HashSet<String>();
        for (int page = 1; page <= Math.max(1, first.totalPages()); page++) {
            var current = page == 1 ? first : readPage(resource, page, started);
            if (current.totalItems() != first.totalItems()
                    || current.totalPages() != first.totalPages()) {
                throw invalidResponse();
            }
            if (page < first.totalPages() && current.items().size() != PAGE_SIZE)
                throw invalidResponse();
            for (var item : current.items()) {
                if (!ids.add(item.id())) {
                    throw invalidResponse();
                }
                items.add(item);
            }
        }
        if (items.size() != first.totalItems()) {
            throw invalidResponse();
        }
        return List.copyOf(items);
    }

    private CatalogPage readPage(CatalogResource resource, int page, long started) {
        checkDeadline(started);
        var result = mapper.page(client.page(resource, page, PAGE_SIZE), page, PAGE_SIZE);
        checkDeadline(started);
        return result;
    }

    private void checkDeadline(long started) {
        if (ticker.getAsLong() - started >= TIME_BUDGET_NANOS) {
            throw new CatalogFailure(CatalogFailure.Reason.TIMEOUT, null);
        }
    }

    private void validatePageCount(CatalogPage page) {
        long expectedPages =
                page.totalItems() / PAGE_SIZE + (page.totalItems() % PAGE_SIZE == 0 ? 0 : 1);
        if (page.totalPages() > MAX_PAGES || page.totalPages() != expectedPages)
            throw invalidResponse();
    }
}
