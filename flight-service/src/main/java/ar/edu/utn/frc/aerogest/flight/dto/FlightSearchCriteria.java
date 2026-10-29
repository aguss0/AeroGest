package ar.edu.utn.frc.aerogest.flight.dto;

import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import java.time.Instant;

/** Filtros opcionales para listar vuelos. Un filtro en null no se aplica. */
public record FlightSearchCriteria(
        String origin,
        String destination,
        FlightStatus status,
        Instant departureFrom,
        Instant departureTo
) {
}
