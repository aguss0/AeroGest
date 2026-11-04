package ar.edu.utn.frc.aerogest.flight.dto;

import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import ar.edu.utn.frc.aerogest.flight.service.DepartureCheckResult;
import java.time.Instant;

/**
 * Resultado de verificar la salida real de un vuelo en OpenSky (RN-16).
 *
 * @param result           DEPARTURE_DETECTED cierra el vuelo; ON_GROUND, OTHER_FLIGHT y NOT_SEEN no cambian nada
 * @param status           estado del vuelo después de la verificación
 * @param observedCallsign callsign que transmite la aeronave (null si no se vio)
 * @param observedOnGround si la aeronave informa estar en tierra (null si no se vio)
 * @param lastContact      último mensaje de la aeronave según OpenSky (null si no se vio)
 */
public record DepartureCheckResponse(
        Long flightId,
        String callsign,
        String aircraftIcao24,
        DepartureCheckResult result,
        FlightStatus status,
        String observedCallsign,
        Boolean observedOnGround,
        Instant lastContact,
        Instant departureCheckedAt,
        Instant departureDetectedAt
) {
}
