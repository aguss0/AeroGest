package ar.edu.utn.frc.aerogest.reservation.controller;

import ar.edu.utn.frc.aerogest.reservation.service.CurrentUser;
import org.springframework.security.core.Authentication;

/** Traduce la autenticación de Spring Security (token de Keycloak) al {@link CurrentUser} de los servicios. */
final class AuthenticatedUser {

    private AuthenticatedUser() {
    }

    /** authentication.getName() es el claim "sub" del JWT. */
    static CurrentUser from(Authentication authentication) {
        boolean admin = authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));
        return new CurrentUser(authentication.getName(), admin);
    }
}
