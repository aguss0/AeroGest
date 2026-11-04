package ar.edu.utn.frc.aerogest.gateway.config;

import ar.edu.utn.frc.aerogest.common.web.CorrelationId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Primer filtro del gateway: fija el X-Correlation-Id de la solicitud (respeta el del cliente
 * o genera uno), lo reenvía al servicio de destino y lo devuelve en la respuesta. Corre antes
 * que Spring Security, así también los 401/403 del gateway llevan el id.
 *
 * Al terminar deja una línea de log por solicitud: es el primer eslabón para seguir una
 * reserva en los logs del gateway, de Reservation Service y de Flight Service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdWebFilter implements WebFilter {

    /** Atributo del exchange con el id, para los manejadores de error del gateway. */
    public static final String ATTRIBUTE = CorrelationIdWebFilter.class.getName() + ".id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationIdWebFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.resolve(exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CorrelationId.HEADER, correlationId))
                .build();
        exchange.getAttributes().put(ATTRIBUTE, correlationId);
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        long start = System.nanoTime();
        return chain.filter(exchange.mutate().request(request).build())
                .doFinally(signal -> log.info("{} {} -> {} ({} ms) [{}]",
                        request.getMethod(), request.getURI().getRawPath(),
                        exchange.getResponse().getStatusCode(),
                        (System.nanoTime() - start) / 1_000_000, correlationId));
    }

    /** Id de la solicitud en curso (lo fija este filtro; si no corrió, no hay id). */
    public static String of(ServerWebExchange exchange) {
        return exchange.getAttribute(ATTRIBUTE);
    }
}
