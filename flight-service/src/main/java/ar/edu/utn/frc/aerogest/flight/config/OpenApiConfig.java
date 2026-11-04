package ar.edu.utn.frc.aerogest.flight.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Documentación OpenAPI: describe el servicio y habilita el botón "Authorize" con un token Bearer.
 *
 * Dos grupos: "public" (/api/**), que es el que publica el gateway, e "internal" (/internal/**),
 * que solo se ve entrando directo al servicio. El servidor es relativo ("/") para que
 * "Try it out" funcione tanto desde el gateway como desde el servicio.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI flightServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AeroGest - Flight Service")
                        .version("v1")
                        .description("Gestión de vuelos y disponibilidad de plazas. "
                                + "Autenticación con tokens JWT emitidos por Keycloak."))
                .addServersItem(new Server().url("/"))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }

    @Bean
    public GroupedOpenApi publicApi() {
        return GroupedOpenApi.builder().group("public").pathsToMatch("/api/**").build();
    }

    @Bean
    public GroupedOpenApi internalApi() {
        return GroupedOpenApi.builder().group("internal").pathsToMatch("/internal/**").build();
    }
}
