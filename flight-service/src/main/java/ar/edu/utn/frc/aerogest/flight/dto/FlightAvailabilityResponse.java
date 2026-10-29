package ar.edu.utn.frc.aerogest.flight.dto;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import java.time.Instant;

/** Contrato de integración: lo que Reservation Service necesita saber de un vuelo. */
public record FlightAvailabilityResponse(
        Long flightId,
        String code,
        FlightStatus status,
        int capacity,
        int availableSeats,
        boolean acceptsReservations
) {

    public static FlightAvailabilityResponse from(Flight flight, Instant now) {
        return new FlightAvailabilityResponse(
                flight.getId(),
                flight.getCode(),
                flight.getStatus(),
                flight.getCapacity(),
                flight.availableSeats(),
                flight.isReservable(now) && flight.availableSeats() > 0);
    }
}
