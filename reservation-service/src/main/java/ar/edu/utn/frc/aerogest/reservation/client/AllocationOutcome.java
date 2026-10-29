package ar.edu.utn.frc.aerogest.reservation.client;

import java.util.UUID;

/**
 * Plaza ocupada en Flight. created=false significa que Flight ya la había asignado a esta reserva
 * (reintento idempotente).
 */
public record AllocationOutcome(UUID reservationId, long flightId, boolean created, int availableSeats) {
}
