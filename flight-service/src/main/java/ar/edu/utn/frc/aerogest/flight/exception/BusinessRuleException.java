package ar.edu.utn.frc.aerogest.flight.exception;

/**
 * Se lanza cuando se viola una regla de negocio. No conoce HTTP: solo lleva
 * un código y un mensaje comprensible. El mapeo a status HTTP está en el manejador de errores.
 */
public class BusinessRuleException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessRuleException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }
}
