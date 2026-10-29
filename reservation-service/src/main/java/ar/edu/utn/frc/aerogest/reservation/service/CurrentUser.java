package ar.edu.utn.frc.aerogest.reservation.service;

import java.util.Objects;

/**
 * Quién hace la operación: el "sub" del token de Keycloak y si tiene rol ADMIN.
 * Los servicios lo reciben explícitamente, así las reglas de acceso (RN-09) no dependen
 * de Spring Security y se prueban sin armar tokens.
 */
public record CurrentUser(String userId, boolean admin) {

    public CurrentUser {
        Objects.requireNonNull(userId, "userId");
    }

    public static CurrentUser user(String userId) {
        return new CurrentUser(userId, false);
    }

    public static CurrentUser admin(String userId) {
        return new CurrentUser(userId, true);
    }

    /** Un ADMIN accede a todo; un USER, solo a lo que registró él. */
    public boolean canAccess(String ownerUserId) {
        return admin || userId.equals(ownerUserId);
    }
}
