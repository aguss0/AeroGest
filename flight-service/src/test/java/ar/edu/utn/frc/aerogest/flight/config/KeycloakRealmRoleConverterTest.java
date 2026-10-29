package ar.edu.utn.frc.aerogest.flight.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/** Los roles de Keycloak se traducen a autoridades ROLE_xxx (base de RN-09). */
class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private Jwt jwtWithClaims(Map<String, Object> claims) {
        return Jwt.withTokenValue("token").header("alg", "none").claims(c -> c.putAll(claims)).build();
    }

    @Test
    void convierteLosRolesDelRealmEnAutoridadesConPrefijoRole() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", Map.of("roles", List.of("ADMIN", "USER"))));

        assertThat(converter.convert(jwt))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_USER");
    }

    @Test
    void sinClaimRealmAccessNoHayAutoridades() {
        Jwt jwt = jwtWithClaims(Map.of("sub", "abc"));

        assertThat(converter.convert(jwt)).isEmpty();
    }

    @Test
    void ignoraUnClaimMalFormado() {
        Jwt jwt = jwtWithClaims(Map.of("realm_access", "no-es-un-mapa"));

        assertThat(converter.convert(jwt)).isEmpty();
    }
}
