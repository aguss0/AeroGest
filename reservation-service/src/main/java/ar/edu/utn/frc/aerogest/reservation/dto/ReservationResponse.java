package ar.edu.utn.frc.aerogest.reservation.dto;

import ar.edu.utn.frc.aerogest.reservation.domain.Reservation;
import ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.UUID;

/** Representación pública de una reserva. */
public record ReservationResponse(
        UUID id,
        Long passengerId,
        Long flightId,
        ReservationStatus status,
        String createdByUserId,
        Instant createdAt,
        Instant updatedAt
) {

    public static ReservationResponse from(Reservation reservation) {
        return new ReservationResponse(
                reservation.getId(),
                reservation.getPassengerId(),
                reservation.getFlightId(),
                reservation.getStatus(),
                reservation.getCreatedByUserId(),
                reservation.getCreatedAt(),
                reservation.getUpdatedAt());
    }
}
