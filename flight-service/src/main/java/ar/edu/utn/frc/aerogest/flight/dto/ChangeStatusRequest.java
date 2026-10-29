package ar.edu.utn.frc.aerogest.flight.dto;

import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import jakarta.validation.constraints.NotNull;

public record ChangeStatusRequest(
        @NotNull(message = "El estado es obligatorio")
        FlightStatus status
) {
}
