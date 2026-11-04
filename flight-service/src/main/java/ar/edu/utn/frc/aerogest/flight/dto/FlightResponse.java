package ar.edu.utn.frc.aerogest.flight.dto;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import java.time.Instant;

/** Representación pública de un vuelo. La entidad JPA nunca se expone directamente. */
public record FlightResponse(
        Long id,
        String code,
        String origin,
        String destination,
        Instant departureAt,
        Instant arrivalAt,
        int capacity,
        int availableSeats,
        FlightStatus status,
        String callsign,
        String aircraftIcao24,
        Instant departureCheckedAt,
        Instant departureDetectedAt
) {

    public static FlightResponse from(Flight flight) {
        return new FlightResponse(
                flight.getId(),
                flight.getCode(),
                flight.getOrigin(),
                flight.getDestination(),
                flight.getDepartureAt(),
                flight.getArrivalAt(),
                flight.getCapacity(),
                flight.availableSeats(),
                flight.getStatus(),
                flight.getCallsign(),
                flight.getAircraftIcao24(),
                flight.getDepartureCheckedAt(),
                flight.getDepartureDetectedAt());
    }
}
