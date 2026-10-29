package ar.edu.utn.frc.aerogest.flight.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Documentación OpenAPI: describe el servicio y habilita el botón "Authorize" con un token Bearer. */
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
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components().addSecuritySchemes(BEARER,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")));
    }
}
