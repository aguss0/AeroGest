package ar.edu.utn.frc.aerogest.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.utn.frc.aerogest.common.web.CorrelationId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Todos los errores de AeroGest tienen la misma estructura: RFC 7807 + code + correlationId. */
class ProblemResponsesTest {

    @AfterEach
    void limpiarMdc() {
        MDC.clear();
    }

    @Test
    @SuppressWarnings("unchecked")
    void armaElCuerpoConElFormatoComunYElCorrelationIdDelMdc() {
        MDC.put(CorrelationId.MDC_KEY, "abc-123");

        ResponseEntity<Object> response = ProblemResponses.of(HttpStatus.CONFLICT, "NO_AVAILABILITY", "Sin plazas");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsExactly(
                Map.entry("type", "about:blank"),
                Map.entry("title", "Conflict"),
                Map.entry("status", 409),
                Map.entry("detail", "Sin plazas"),
                Map.entry("code", "NO_AVAILABILITY"),
                Map.entry("correlationId", "abc-123"));
    }

    @Test
    void incluyeLosCamposInvalidosSoloSiLosHay() {
        Map<String, Object> conErrores = ProblemResponses.body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Datos inválidos", null, List.of(new FieldIssue("code", "obligatorio")));
        Map<String, Object> sinErrores = ProblemResponses.body(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                "Datos inválidos", null, null);

        assertThat(conErrores).containsEntry("errors", List.of(new FieldIssue("code", "obligatorio")))
                .doesNotContainKey("correlationId");
        assertThat(sinErrores).doesNotContainKey("errors");
    }

    @Test
    void serializaAJsonEscapandoLosTextos() {
        String json = ProblemResponses.json(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Falta un \"token\"\nválido", "id-1");

        assertThat(json).isEqualTo("{\"type\":\"about:blank\",\"title\":\"Unauthorized\",\"status\":401,"
                + "\"detail\":\"Falta un \\\"token\\\"\\nválido\",\"code\":\"UNAUTHENTICATED\",\"correlationId\":\"id-1\"}");
    }
}
