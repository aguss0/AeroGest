package ar.edu.utn.frc.aerogest.flight.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.OAuthFlow;
import io.swagger.v3.oas.models.security.OAuthFlows;
import io.swagger.v3.oas.models.security.Scopes;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import org.springframework.beans.factory.annotation.Value;
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
    private static final String KEYCLOAK = "keycloak";

    @Bean
    public OpenAPI flightServiceOpenApi(@Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuer) {
        String oidc = issuer + "/protocol/openid-connect";
        return new OpenAPI()
                .info(new Info()
                        .title("AeroGest - Flight Service")
                        .version("v1")
                        .description("Gestión de vuelos y disponibilidad de plazas. "
                                + "Autenticación con tokens JWT emitidos por Keycloak."))
                .addServersItem(new Server().url("/"))
                // Dos formas de autorizarse (cualquiera de las dos): login de Keycloak o un token pegado
                .addSecurityItem(new SecurityRequirement().addList(KEYCLOAK))
                .addSecurityItem(new SecurityRequirement().addList(BEARER))
                .components(new Components()
                        // Login OIDC real: authorization code + PKCE con el cliente público aerogest-api
                        .addSecuritySchemes(KEYCLOAK, new SecurityScheme()
                                .type(SecurityScheme.Type.OAUTH2)
                                .description("Login en Keycloak (OIDC, authorization code + PKCE). client_id: aerogest-api, sin secreto")
                                .flows(new OAuthFlows().authorizationCode(new OAuthFlow()
                                        .authorizationUrl(oidc + "/auth")
                                        .tokenUrl(oidc + "/token")
                                        .scopes(new Scopes().addString("openid", "Identidad OIDC")))))
                        .addSecuritySchemes(BEARER, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Token pegado a mano (scripts/get-token.ps1)")));
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
