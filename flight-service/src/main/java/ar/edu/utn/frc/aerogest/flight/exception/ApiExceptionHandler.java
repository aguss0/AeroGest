package ar.edu.utn.frc.aerogest.flight.exception;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Manejo central de errores. Todas las respuestas siguen la estructura de RFC 7807
 * (type, title, status, detail) más un campo "code" estable en la raíz del JSON, que es
 * el que usan los clientes (incluido Reservation Service) para distinguir el motivo.
 * El cuerpo se arma como un mapa para garantizar esa estructura sin depender de la
 * configuración de serialización de Jackson.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex) {
        HttpStatus status = statusFor(ex.getErrorCode());
        log.debug("Regla de negocio violada [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return respond(status, ex.getErrorCode().name(), ex.getMessage(), null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Object> handleValidation(MethodArgumentNotValidException ex) {
        List<FieldIssue> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldIssue(e.getField(), e.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "La solicitud contiene datos inválidos", errors);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> handleUnreadable(HttpMessageNotReadableException ex) {
        return respond(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST",
                "El cuerpo de la solicitud está mal formado o contiene valores no válidos", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Object> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return respond(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER",
                "El parámetro '" + ex.getName() + "' tiene un valor inválido", null);
    }

    /** Dos modificaciones simultáneas del mismo vuelo: gana una, la otra debe reintentar. */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex) {
        return respond(HttpStatus.CONFLICT, ErrorCode.CONCURRENT_MODIFICATION.name(),
                "El vuelo fue modificado por otra operación. Volvé a consultarlo y reintentá", null);
    }

    /** Una restricción de la base (CHECK, UNIQUE) frenó una operación que las validaciones no detectaron. */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleIntegrity(DataIntegrityViolationException ex) {
        log.warn("Violación de integridad de datos: {}", ex.getMostSpecificCause().getMessage());
        return respond(HttpStatus.CONFLICT, "DATA_INTEGRITY_VIOLATION",
                "La operación entra en conflicto con el estado actual de los datos", null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Object> handleAccessDenied(AccessDeniedException ex) {
        return respond(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "No tenés permisos para realizar esta operación", null);
    }

    /**
     * Último recurso. Los errores propios de Spring MVC (ruta inexistente, método no permitido,
     * etc.) ya traen su ProblemDetail: se respetan. Cualquier otra cosa es un error inesperado:
     * se registra completo y se responde sin detalles internos.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex) {
        if (ex instanceof ErrorResponse errorResponse) {
            return ResponseEntity.status(errorResponse.getStatusCode())
                    .headers(errorResponse.getHeaders())
                    .body(errorResponse.getBody());
        }
        log.error("Error inesperado", ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Ocurrió un error inesperado", null);
    }

    private static HttpStatus statusFor(ErrorCode code) {
        return switch (code) {
            case FLIGHT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID_SCHEDULE, INVALID_ROUTE, DEPARTURE_IN_PAST, INVALID_CAPACITY -> HttpStatus.BAD_REQUEST;
            case DUPLICATE_FLIGHT_CODE, INVALID_STATUS_TRANSITION, FLIGHT_NOT_MODIFIABLE,
                 FLIGHT_NOT_RESERVABLE, NO_AVAILABILITY, ALLOCATION_CONFLICT, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
        };
    }

    private static ResponseEntity<Object> respond(HttpStatus status, String code, String detail, List<FieldIssue> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        String correlationId = MDC.get("correlationId");
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        if (errors != null) {
            body.put("errors", errors);
        }
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }

    /** Un campo inválido de la solicitud. */
    public record FieldIssue(String field, String message) {
    }
}