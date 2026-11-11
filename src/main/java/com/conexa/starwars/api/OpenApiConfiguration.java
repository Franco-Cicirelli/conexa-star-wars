package com.conexa.starwars.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the API's Bearer scheme while leaving requirements on catalog operations only. A
 * relative server URL keeps Swagger requests on the browser's HTTPS origin without trusting proxy
 * headers.
 */
@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI catalogOpenApi() {
        return new OpenAPI()
                .info(
                        new Info()
                                .title("Conexa Star Wars API")
                                .version("v1")
                                .description(
                                        "Register an in-memory account, log in, and paste accessToken into Authorize. Catalog operations require catalog:read."))
                .servers(List.of(new Server().url("/").description("Current API origin")))
                .components(
                        new Components()
                                .addSecuritySchemes(
                                        "bearerAuth",
                                        new SecurityScheme()
                                                .type(SecurityScheme.Type.HTTP)
                                                .scheme("bearer")
                                                .bearerFormat("JWT")
                                                .description(
                                                        "Paste only accessToken from POST /api/v1/auth/login. Swagger UI adds the Bearer prefix. Catalog access requires catalog:read.")));
    }
}
