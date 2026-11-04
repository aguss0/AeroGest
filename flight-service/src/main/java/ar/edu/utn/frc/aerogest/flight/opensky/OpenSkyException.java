package ar.edu.utn.frc.aerogest.flight.opensky;

import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import java.time.Duration;

/**
 * Falla de la integración con OpenSky. Nunca es evidencia de despegue: quien la recibe no
 * cambia el estado del vuelo (RN-16). El código indica el tipo de falla.
 */
public class OpenSkyException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Duration retryAfter;

    public OpenSkyException(ErrorCode errorCode, String message, Throwable cause) {
        this(errorCode, message, null, cause);
    }

    public OpenSkyException(ErrorCode errorCode, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.retryAfter = retryAfter;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /** Solo en OPENSKY_RATE_LIMITED: cuánto esperar antes de volver a consultar (puede ser null). */
    public Duration getRetryAfter() {
        return retryAfter;
    }
}
