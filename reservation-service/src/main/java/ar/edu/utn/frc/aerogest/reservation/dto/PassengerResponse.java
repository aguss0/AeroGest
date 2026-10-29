package ar.edu.utn.frc.aerogest.reservation.dto;

import ar.edu.utn.frc.aerogest.reservation.domain.DocumentType;
import ar.edu.utn.frc.aerogest.reservation.domain.Passenger;
import java.time.Instant;

/** Representación pública de un pasajero. La entidad JPA nunca se expone directamente. */
public record PassengerResponse(
        Long id,
        String firstName,
        String lastName,
        DocumentType documentType,
        String documentNumber,
        String ownerUserId,
        Instant createdAt,
        Instant updatedAt
) {

    public static PassengerResponse from(Passenger passenger) {
        return new PassengerResponse(
                passenger.getId(),
                passenger.getFirstName(),
                passenger.getLastName(),
                passenger.getDocumentType(),
                passenger.getDocumentNumber(),
                passenger.getOwnerUserId(),
                passenger.getCreatedAt(),
                passenger.getUpdatedAt());
    }
}
