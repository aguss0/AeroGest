package ar.edu.utn.frc.aerogest.reservation.config;

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
 * Grupo "public" (/api/**), que es el que publica el gateway. El servidor es relativo ("/")
 * para que "Try it out" funcione tanto desde el gateway como desde el servicio.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER = "bearerAuth";

    @Bean
    public OpenAPI reservationServiceOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AeroGest - Reservation Service")
                        .version("v1")
                        .description("Pasajeros y reservas. Cada reserva se confirma solo después de que "
                                + "Flight Service asigna la plaza. Autenticación con tokens JWT emitidos por Keycloak."))
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
}
