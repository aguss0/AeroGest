package ar.edu.utn.frc.aerogest.reservation.exception;

/**
 * Fallo de integración con Flight Service cuyo resultado es INCIERTO (RN-10): no sabemos si la
 * plaza quedó ocupada o no. La reserva nunca se confirma; el servicio de reservas intenta liberar
 * la plaza y responde 503/504. Las respuestas definitivas de Flight (404, 409) NO usan esta clase:
 * se lanzan como {@link BusinessRuleException} común.
 */
public class FlightServiceException extends BusinessRuleException {

    public FlightServiceException(ErrorCode errorCode, String message) {
        super(errorCode, message);
    }

    public FlightServiceException(ErrorCode errorCode, String message, Throwable cause) {
        super(errorCode, message);
        initCause(cause);
    }
}
