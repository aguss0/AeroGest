package ar.edu.utn.frc.aerogest.common.web;

import ar.edu.utn.frc.aerogest.common.error.ProblemResponses;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Escribe un error con el formato de {@link ProblemResponses} directamente en la respuesta
 * servlet. Lo usan los manejadores 401/403 de Spring Security, que corren antes de llegar a
 * los controladores y por eso no pasan por el @RestControllerAdvice.
 */
public final class ServletProblemWriter {

    private ServletProblemWriter() {
    }

    public static void write(HttpServletResponse response, HttpStatus status, String code, String detail)
            throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(ProblemResponses.json(status, code, detail, MDC.get(CorrelationId.MDC_KEY)));
    }
}
