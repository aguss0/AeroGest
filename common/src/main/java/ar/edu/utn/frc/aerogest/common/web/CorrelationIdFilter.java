package ar.edu.utn.frc.aerogest.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Trazabilidad entre servicios: toma el encabezado X-Correlation-Id (o genera uno), lo pone
 * en el log (MDC) y lo devuelve en la respuesta. Así una reserva se puede seguir en los logs
 * del gateway y de ambos servicios. Corre antes que Spring Security para que también cubra
 * los 401 y 403.
 *
 * Lo registra {@link ar.edu.utn.frc.aerogest.common.autoconfigure.AerogestCommonAutoConfiguration}
 * en las aplicaciones servlet; los servicios no tienen que declararlo.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String correlationId = CorrelationId.resolve(request.getHeader(CorrelationId.HEADER));
        MDC.put(CorrelationId.MDC_KEY, correlationId);
        response.setHeader(CorrelationId.HEADER, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
