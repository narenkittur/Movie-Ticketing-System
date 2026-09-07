package com.example.movieticket.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * Global OpenAPI/Swagger wiring (Module 3, springdoc-openapi-starter-webmvc-ui in
 * pom.xml). This class carries no @Bean methods - it exists purely to hold
 * class-level annotations that springdoc scans once at startup to build the base
 * OpenAPI document, so it only needs to exist on the classpath as a
 * @Configuration, never to be looked up or injected anywhere.
 *
 * @SecurityScheme defines the "bearerAuth" scheme referenced by every controller
 * method annotated with @SecurityRequirement(name = "bearerAuth")
 * (MovieController, ShowController, SeatController's admin endpoints) - without
 * this definition, that name would be a dangling reference and Swagger UI would
 * have no "Authorize" button to attach a JWT to protected requests.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Movie Ticket Booking API",
        version = "v1",
        description = "Movie/show catalog, seat inventory, booking, and payment endpoints."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Paste the access token returned by POST /auth/login (without the 'Bearer ' prefix - Swagger UI adds it).")
public class OpenApiConfig {
}
