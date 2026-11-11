package com.conexa.starwars.catalog.infrastructure.swapi;

import static com.conexa.starwars.catalog.application.CatalogFailure.invalidResponse;

import com.conexa.starwars.catalog.application.CatalogFailure;
import com.conexa.starwars.catalog.application.CatalogResource;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.CollectionEnvelope;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.DetailEnvelope;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.DetailItem;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.ErrorEnvelope;
import com.conexa.starwars.catalog.infrastructure.swapi.SwapiResponses.PageEnvelope;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriBuilder;

@Component
class SwapiClient {
    private final RestClient client;
    private final SwapiCircuit circuit;
    private final SwapiResponseMapper mapper;

    SwapiClient(
            RestClient.Builder builder,
            @Value("${swapi.base-url}") String baseUrl,
            SwapiCircuit circuit,
            SwapiResponseMapper mapper) {
        client = builder.baseUrl(baseUrl).build();
        this.circuit = circuit;
        this.mapper = mapper;
    }

    PageEnvelope page(CatalogResource resource, int page, int size) {
        return read(
                uri ->
                        uri.path("/{resource}")
                                .queryParam("page", page)
                                .queryParam("limit", size)
                                .build(resource.path()),
                PageEnvelope.class,
                response -> mapper.page(response, page, size));
    }

    CollectionEnvelope films() {
        return read(
                uri -> uri.path("/films").build(),
                CollectionEnvelope.class,
                response -> mapper.collection(CatalogResource.FILMS, response));
    }

    CollectionEnvelope search(CatalogResource resource, String name) {
        return read(
                uri ->
                        uri.path("/{resource}/")
                                .queryParam("name", "{name}")
                                .build(Map.of("resource", resource.path(), "name", name)),
                CollectionEnvelope.class,
                response -> mapper.collection(resource, response));
    }

    Optional<DetailItem> detail(CatalogResource resource, String id) {
        return circuit.call(
                () -> translateErrors(() -> readDetail(resource, id)), Optional::isEmpty);
    }

    private <T> T read(Function<UriBuilder, URI> uri, Class<T> responseType, Consumer<T> validate) {
        return circuit.call(
                () ->
                        translateErrors(
                                () -> {
                                    T response =
                                            client.get().uri(uri).retrieve().body(responseType);
                                    validate.accept(response);
                                    return response;
                                }),
                this::isConfirmedAbsence);
    }

    private boolean isConfirmedAbsence(Object response) {
        if (response instanceof CollectionEnvelope collection) {
            return "ok".equals(collection.message())
                    && collection.result() != null
                    && collection.result().isEmpty()
                    && collection.next() == null
                    && collection.totalRecords() == null
                    && collection.totalPages() == null;
        }
        if (response instanceof PageEnvelope page) {
            return "ok".equals(page.message())
                    && page.results() != null
                    && page.results().isEmpty()
                    && Long.valueOf(0).equals(page.totalRecords())
                    && Integer.valueOf(0).equals(page.totalPages());
        }
        return false;
    }

    private Optional<DetailItem> readDetail(CatalogResource resource, String id) {
        try {
            var response =
                    client.get()
                            .uri("/{resource}/{id}", resource.path(), id)
                            .retrieve()
                            .body(DetailEnvelope.class);
            if (response == null
                    || !"ok".equals(response.message())
                    || response.result() == null
                    || !id.equals(response.result().uid())) {
                throw invalidResponse();
            }
            mapper.detail(resource, response.result());
            return Optional.of(response.result());
        } catch (RestClientResponseException exception) {
            if (exception.getStatusCode().value() != 404) {
                throw exception;
            }
            validateAbsence(resource, exception.getResponseBodyAs(ErrorEnvelope.class));
            return Optional.empty();
        }
    }

    private void validateAbsence(CatalogResource resource, ErrorEnvelope body) {
        String expected =
                switch (resource) {
                    case PEOPLE -> "not found";
                    case FILMS -> "Film not found";
                    case STARSHIPS, VEHICLES -> "Not found";
                };
        if (body == null
                || !expected.equals(body.message())
                || body.result() != null
                || body.results() != null) {
            throw invalidResponse();
        }
    }

    private <T> T translateErrors(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RestClientResponseException exception) {
            var reason =
                    exception.getStatusCode().is5xxServerError()
                                    || exception.getStatusCode().value() == 429
                            ? CatalogFailure.Reason.UNAVAILABLE
                            : CatalogFailure.Reason.INVALID_RESPONSE;
            throw new CatalogFailure(reason, exception);
        } catch (ResourceAccessException exception) {
            Throwable cause = exception;
            while (cause != null) {
                if (cause instanceof SocketTimeoutException
                        || cause instanceof HttpTimeoutException) {
                    throw new CatalogFailure(CatalogFailure.Reason.TIMEOUT, exception);
                }
                cause = cause.getCause();
            }
            throw new CatalogFailure(CatalogFailure.Reason.UNAVAILABLE, exception);
        } catch (RestClientException exception) {
            throw new CatalogFailure(CatalogFailure.Reason.INVALID_RESPONSE, exception);
        }
    }
}
