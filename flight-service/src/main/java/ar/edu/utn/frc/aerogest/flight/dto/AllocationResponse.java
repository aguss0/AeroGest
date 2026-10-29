package ar.edu.utn.frc.aerogest.flight.dto;

import ar.edu.utn.frc.aerogest.flight.domain.AllocationStatus;
import java.util.UUID;

public record AllocationResponse(
        UUID reservationId,
        Long flightId,
        AllocationStatus status,
        int availableSeats
) {
}
