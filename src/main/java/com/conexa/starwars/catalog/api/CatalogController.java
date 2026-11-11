package com.conexa.starwars.catalog.api;

import com.conexa.starwars.catalog.application.CatalogPage;
import com.conexa.starwars.catalog.application.CatalogResource;
import com.conexa.starwars.catalog.application.CatalogService;
import com.conexa.starwars.ratelimit.RequestQuotas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.beans.PropertyEditorSupport;
import java.util.Locale;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@SecurityRequirement(name = "bearerAuth")
public class CatalogController {
    private final CatalogService service;
    private final RequestQuotas quotas;

    public CatalogController(CatalogService service, RequestQuotas quotas) {
        this.service = service;
        this.quotas = quotas;
    }

    @InitBinder
    void trimStrings(WebDataBinder binder) {
        binder.registerCustomEditor(
                String.class,
                new PropertyEditorSupport() {
                    @Override
                    public void setAsText(String text) {
                        setValue(text == null ? null : text.strip());
                    }
                });
    }

    @GetMapping("/api/v1/{resource:people|films|starships|vehicles}")
    @Operation(
            summary = "Query the Star Wars catalog",
            description =
                    "List a resource with pagination and optional UID/name filters. Both filters must match when combined. Requires catalog:read; authorized requests, including cache hits, consume the per-user quota.")
    public CatalogPage list(
            @Parameter(
                            schema =
                                    @Schema(
                                            allowableValues = {
                                                "people",
                                                "films",
                                                "starships",
                                                "vehicles"
                                            }))
                    @PathVariable
                    String resource,
            @RequestParam(defaultValue = "1") @Min(1) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(10) int size,
            @RequestParam(required = false) @Pattern(regexp = "0*[1-9][0-9]*") String id,
            @RequestParam(required = false)
                    @Size(min = 1, max = 80)
                    @Pattern(regexp = "(?s).*\\S.*")
                    String name,
            @Parameter(hidden = true) Authentication authentication) {
        quotas.catalog(authentication.getName());
        return service.find(
                CatalogResource.valueOf(resource.toUpperCase(Locale.ROOT)), page, size, id, name);
    }
}
