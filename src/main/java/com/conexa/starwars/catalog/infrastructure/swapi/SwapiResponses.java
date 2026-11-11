package com.conexa.starwars.catalog.infrastructure.swapi;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

final class SwapiResponses {
    private SwapiResponses() {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PageEnvelope(
            String message,
            List<SwapiItem> results,
            @JsonProperty("total_records") Long totalRecords,
            @JsonProperty("total_pages") Integer totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SwapiItem(String uid, String name) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetailItem(String uid, Properties properties) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Properties(String name, String title) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record DetailEnvelope(String message, DetailItem result) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CollectionEnvelope(
            String message,
            List<DetailItem> result,
            String next,
            @JsonProperty("total_records") Long totalRecords,
            @JsonProperty("total_pages") Integer totalPages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ErrorEnvelope(String message, Object result, Object results) {}
}
