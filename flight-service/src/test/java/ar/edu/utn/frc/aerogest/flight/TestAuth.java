package ar.edu.utn.frc.aerogest.flight;

import ar.edu.utn.frc.aerogest.flight.config.KeycloakRealmRoleConverter;
import java.util.List;
import java.util.Map;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tokens simulados con la misma forma que los de Keycloak (roles en realm_access.roles).
 * Se pasan por el KeycloakRealmRoleConverter real, así las pruebas cubren esa conversión.
 */
final class TestAuth {

    private TestAuth() {
    }

    static RequestPostProcessor admin() {
        return withRoles("ADMIN");
    }

    static RequestPostProcessor user() {
        return withRoles("USER");
    }

    static RequestPostProcessor service() {
        return withRoles("SERVICE");
    }

    static RequestPostProcessor withRoles(String... roles) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt
                        .subject("usuario-de-prueba")
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
