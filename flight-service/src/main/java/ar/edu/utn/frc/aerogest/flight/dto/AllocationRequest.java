package ar.edu.utn.frc.aerogest.flight.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AllocationRequest(
        @NotNull(message = "El id de la reserva es obligatorio")
        UUID reservationId
) {
}
