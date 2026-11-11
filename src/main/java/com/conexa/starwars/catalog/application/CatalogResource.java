package com.conexa.starwars.catalog.application;

public enum CatalogResource {
    PEOPLE("people"),
    FILMS("films"),
    STARSHIPS("starships"),
    VEHICLES("vehicles");

    private final String path;

    CatalogResource(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
