package ar.edu.utn.frc.aerogest.reservation.client;

import java.util.UUID;

/**
 * Puerto hacia Flight Service. Los servicios de negocio dependen de esta interfaz, no de HTTP,
 * así se pueden probar con dobles.
 *
 * <p>Errores: las respuestas definitivas de Flight (vuelo inexistente, sin plazas, no reservable,
 * conflicto) salen como {@code BusinessRuleException}; los fallos de comunicación o respuestas
 * inválidas (resultado incierto) salen como {@code FlightServiceException}.
 */
public interface FlightGateway {

    /** Ocupa una plaza para la reserva. Idempotente por reservationId. */
    AllocationOutcome allocateSeat(long flightId, UUID reservationId);

    /** Libera la plaza de la reserva. Idempotente: si no hay nada que liberar, no falla. */
    void releaseSeat(long flightId, UUID reservationId);
}
