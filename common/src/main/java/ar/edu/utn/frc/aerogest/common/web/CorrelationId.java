package ar.edu.utn.frc.aerogest.common.web;

import java.util.UUID;

/**
 * Convención de trazabilidad de AeroGest: el encabezado X-Correlation-Id identifica una
 * solicitud de punta a punta (gateway, Reservation Service y Flight Service) y va al log
 * con la clave "correlationId" del MDC.
 */
public final class CorrelationId {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";

    private static final int MAX_LENGTH = 100;

    private CorrelationId() {
    }

    /** Devuelve el id recibido si es razonable; si falta o es sospechoso (muy largo), genera uno nuevo. */
    public static String resolve(String received) {
        if (received == null || received.isBlank() || received.length() > MAX_LENGTH) {
            return UUID.randomUUID().toString();
        }
        return received;
    }
}
