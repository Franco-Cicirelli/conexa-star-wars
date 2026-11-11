package com.conexa.starwars.catalog.api;

import com.conexa.starwars.catalog.application.CatalogPage;
import com.conexa.starwars.catalog.application.CatalogResource;
import com.conexa.starwars.catalog.application.CatalogService;
import com.conexa.starwars.ratelimit.RequestQuotas;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
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
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description =
                        "Catalog page; confirmed absence returns empty items and matching totals",
                content = @Content(schema = @Schema(implementation = CatalogPage.class))),
        @ApiResponse(
                responseCode = "400",
                description = "Invalid page, size, UID, or name (CATALOG_INVALID_REQUEST)",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        org.springframework.http.ProblemDetail
                                                                .class))),
        @ApiResponse(
                responseCode = "401",
                description = "Missing, invalid, or expired Bearer token; body may be empty",
                content = @Content),
        @ApiResponse(
                responseCode = "403",
                description = "Valid token without catalog:read; body may be empty",
                content = @Content),
        @ApiResponse(
                responseCode = "429",
                description =
                        "Local per-user quota exceeded (RATE_LIMIT_EXCEEDED); no provider call",
                headers =
                        @Header(
                                name = "Retry-After",
                                description = "Conservative refresh period in whole seconds",
                                schema = @Schema(type = "integer", format = "int64")),
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        org.springframework.http.ProblemDetail
                                                                .class))),
        @ApiResponse(
                responseCode = "502",
                description = "Incompatible provider response (CATALOG_INVALID_RESPONSE)",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        org.springframework.http.ProblemDetail
                                                                .class))),
        @ApiResponse(
                responseCode = "503",
                description =
                        "Provider unavailable/quota exceeded (CATALOG_UNAVAILABLE) or circuit rejects work (SWAPI_CIRCUIT_OPEN)",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        org.springframework.http.ProblemDetail
                                                                .class))),
        @ApiResponse(
                responseCode = "504",
                description =
                        "Provider timeout or complete-read deadline exceeded (CATALOG_TIMEOUT)",
                content =
                        @Content(
                                mediaType = "application/problem+json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        org.springframework.http.ProblemDetail
                                                                .class)))
    })
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
