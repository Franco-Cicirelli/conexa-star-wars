package com.conexa.starwars.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.conexa.starwars.support.CatalogIntegrationSupport;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenApiIT extends CatalogIntegrationSupport {
    @Test
    void swaggerUiAndAssetsArePublic() throws Exception {
        var page = rawGet("/swagger-ui/index.html", null);
        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Type").orElseThrow()).contains("text/html");
        assertThat(page.body()).contains("Swagger UI", "swagger-ui-bundle.js");
        assertThat(rawGet("/swagger-ui/swagger-ui-bundle.js", null).statusCode()).isEqualTo(200);
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void openApiDocumentsBearerOnlyForCatalogAndUsesCurrentOrigin() throws Exception {
        var response = rawGet("/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var document = JsonPath.parse(response.body());
        assertThat(document.<String>read("$.components.securitySchemes.bearerAuth.type"))
                .isEqualTo("http");
        assertThat(document.<String>read("$.components.securitySchemes.bearerAuth.scheme"))
                .isEqualTo("bearer");
        assertThat(document.<String>read("$.components.securitySchemes.bearerAuth.bearerFormat"))
                .isEqualTo("JWT");
        assertThat(document.<String>read("$.servers[0].url")).isEqualTo("/");
        var paths = document.<java.util.Map<String, Object>>read("$.paths");
        assertThat(paths)
                .containsKeys("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/{resource}");
        assertThat(document.<List<?>>read("$.paths['/api/v1/{resource}'].get.security")).hasSize(1);
        assertThat(
                        document.<java.util.Map<String, Object>>read(
                                "$.paths['/api/v1/{resource}'].get.security[0]"))
                .containsKey("bearerAuth");
        for (String path : List.of("/api/v1/auth/login", "/api/v1/auth/register")) {
            var operation =
                    document.<java.util.Map<String, Object>>read("$.paths['" + path + "'].post");
            assertThat(operation.get("security")).isNull();
        }
        assertThat(rawGet("/api/v1/people", null).statusCode()).isEqualTo(401);
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void documentedStatusesMatchRegistrationAndCatalogContracts() throws Exception {
        var response = rawGet("/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var document = JsonPath.parse(response.body());
        var registration =
                document.<java.util.Map<String, Object>>read(
                        "$.paths['/api/v1/auth/register'].post.responses");
        assertThat(registration).containsKeys("201", "400", "409", "429").doesNotContainKey("200");
        assertThat(
                        document.<java.util.Map<String, Object>>read(
                                "$.paths['/api/v1/auth/login'].post.responses"))
                .containsKeys("200", "400", "401", "429");
        assertThat(
                        document.<java.util.Map<String, Object>>read(
                                "$.paths['/api/v1/{resource}'].get.responses"))
                .containsKeys("200", "400", "401", "403", "429", "502", "503", "504");
        assertThat(
                        document.<String>read(
                                "$.paths['/api/v1/{resource}'].get.responses['429'].headers['Retry-After'].schema.type"))
                .isEqualTo("integer");
        var unauthorized =
                document.<java.util.Map<String, Object>>read(
                        "$.paths['/api/v1/{resource}'].get.responses['401']");
        assertThat(unauthorized).doesNotContainKey("content");
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }

    @Test
    void swaggerConfigurationPointsToThisApiAndDoesNotPersistTokens() throws Exception {
        var response = rawGet("/v3/api-docs/swagger-config", null);
        assertThat(response.statusCode()).isEqualTo(200);
        var document = JsonPath.parse(response.body());
        assertThat(document.<String>read("$.url")).isEqualTo("/v3/api-docs");
        assertThat(document.<Boolean>read("$.persistAuthorization")).isFalse();
        assertThat(swapi.getAllServeEvents()).isEmpty();
    }
}
