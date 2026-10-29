package ar.edu.utn.frc.aerogest.reservation.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de una reserva y sus transiciones válidas (RN-08).
 *
 * PENDING   -> CONFIRMED (Flight Service asignó la plaza) o REJECTED (no hubo plaza o el vuelo no es reservable)
 * CONFIRMED -> CANCELLED
 * CANCELLED y REJECTED son terminales y no ocupan disponibilidad.
 */
public enum ReservationStatus {
    PENDING,
    CONFIRMED,
    CANCELLED,
    REJECTED;

    public Set<ReservationStatus> allowedTransitions() {
        return switch (this) {
            case PENDING -> EnumSet.of(CONFIRMED, REJECTED);
            case CONFIRMED -> EnumSet.of(CANCELLED);
            case CANCELLED, REJECTED -> EnumSet.noneOf(ReservationStatus.class);
        };
    }

    public boolean canTransitionTo(ReservationStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }

    /** Una reserva activa es la que cuenta para RN-06 (no duplicados) y puede ocupar una plaza. */
    public boolean isActive() {
        return this == PENDING || this == CONFIRMED;
    }
}
