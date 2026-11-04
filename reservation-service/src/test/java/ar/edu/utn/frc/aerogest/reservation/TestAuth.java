package ar.edu.utn.frc.aerogest.reservation;

import ar.edu.utn.frc.aerogest.common.security.KeycloakRealmRoleConverter;
import java.util.List;
import java.util.Map;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Tokens simulados con la misma forma que los de Keycloak (roles en realm_access.roles y el
 * usuario en "sub"). Se pasan por el KeycloakRealmRoleConverter real, así las pruebas cubren esa conversión.
 */
final class TestAuth {

    static final String USER_1 = "user-1";
    static final String USER_2 = "user-2";

    private TestAuth() {
    }

    static RequestPostProcessor user(String subject) {
        return token(subject, "USER");
    }

    static RequestPostProcessor admin() {
        return token("admin-1", "ADMIN");
    }

    static RequestPostProcessor service() {
        return token("service-account-reservation-service", "SERVICE");
    }

    static RequestPostProcessor token(String subject, String... roles) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt
                        .subject(subject)
                        .claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }
}
