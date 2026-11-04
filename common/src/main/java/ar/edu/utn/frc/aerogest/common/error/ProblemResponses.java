package ar.edu.utn.frc.aerogest.common.error;

import ar.edu.utn.frc.aerogest.common.web.CorrelationId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Formato único de error de AeroGest: RFC 7807 (type, title, status, detail) más un campo
 * "code" estable en la raíz del JSON, el correlationId y, si corresponde, la lista "errors".
 * Lo usan los manejadores de errores y la seguridad de los servicios, y el gateway.
 *
 * Qué "code" corresponde a cada situación y con qué status se responde NO está acá: es el
 * contrato de cada servicio (su ErrorCode).
 */
public final class ProblemResponses {

    private ProblemResponses() {
    }

    /** Respuesta de error para un controlador; toma el correlationId del MDC. */
    public static ResponseEntity<Object> of(HttpStatus status, String code, String detail) {
        return of(status, code, detail, null);
    }

    /** Respuesta de error con la lista de campos inválidos; toma el correlationId del MDC. */
    public static ResponseEntity<Object> of(HttpStatus status, String code, String detail, List<FieldIssue> errors) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(body(status, code, detail, MDC.get(CorrelationId.MDC_KEY), errors));
    }

    /** Cuerpo del error como mapa ordenado. El correlationId y los errores son opcionales. */
    public static Map<String, Object> body(HttpStatus status, String code, String detail,
                                           String correlationId, List<FieldIssue> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("code", code);
        if (correlationId != null) {
            body.put("correlationId", correlationId);
        }
        if (errors != null) {
            body.put("errors", errors);
        }
        return body;
    }

    /**
     * El mismo cuerpo serializado a JSON, para los lugares que escriben la respuesta a mano
     * (los 401/403 de Spring Security y el gateway), sin depender de la versión de Jackson.
     */
    public static String json(HttpStatus status, String code, String detail, String correlationId) {
        StringBuilder json = new StringBuilder("{");
        body(status, code, detail, correlationId, null).forEach((key, value) -> {
            if (json.length() > 1) {
                json.append(',');
            }
            json.append(quote(key)).append(':');
            json.append(value instanceof Number ? value.toString() : quote(String.valueOf(value)));
        });
        return json.append('}').toString();
    }

    private static String quote(String text) {
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> quoted.append("\\\"");
                case '\\' -> quoted.append("\\\\");
                case '\n' -> quoted.append("\\n");
                case '\r' -> quoted.append("\\r");
                case '\t' -> quoted.append("\\t");
                default -> {
                    if (c < 0x20) {
                        quoted.append(String.format("\\u%04x", (int) c));
                    } else {
                        quoted.append(c);
                    }
                }
            }
        }
        return quoted.append('"').toString();
    }
}
