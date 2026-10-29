package ar.edu.utn.frc.aerogest.reservation.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** Solicitud de reserva: qué pasajero (propio del usuario, RN-09) viaja en qué vuelo. */
public record CreateReservationRequest(
        @NotNull(message = "El pasajero es obligatorio")
        @Positive(message = "El id del pasajero debe ser positivo")
        Long passengerId,

        @NotNull(message = "El vuelo es obligatorio")
        @Positive(message = "El id del vuelo debe ser positivo")
        Long flightId
) {
}
