package ar.edu.utn.frc.aerogest.flight.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Estados de un vuelo y sus transiciones válidas (RN-08).
 *
 * SCHEDULED -> CLOSED, CANCELLED
 * CLOSED    -> SCHEDULED, CANCELLED, COMPLETED
 * CANCELLED y COMPLETED son terminales.
 */
public enum FlightStatus {
    SCHEDULED,   // programado: admite reservas
    CLOSED,      // ventas cerradas: no admite reservas nuevas, se puede reabrir
    CANCELLED,   // terminal
    COMPLETED;   // terminal

    public Set<FlightStatus> allowedTransitions() {
        return switch (this) {
            case SCHEDULED -> EnumSet.of(CLOSED, CANCELLED);
            case CLOSED -> EnumSet.of(SCHEDULED, CANCELLED, COMPLETED);
            case CANCELLED, COMPLETED -> EnumSet.noneOf(FlightStatus.class);
        };
    }

    public boolean canTransitionTo(FlightStatus target) {
        return allowedTransitions().contains(target);
    }

    public boolean isTerminal() {
        return allowedTransitions().isEmpty();
    }
}
