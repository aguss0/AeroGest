package ar.edu.utn.frc.aerogest.gateway.config;

import ar.edu.utn.frc.aerogest.common.error.ProblemResponses;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Escribe los errores propios del gateway (401, 403, 404, 503, 504) con el mismo formato que
 * los microservicios (ProblemResponses de aerogest-common), así el cliente interpreta igual
 * un error del gateway que uno de un servicio.
 */
final class GatewayProblemWriter {

    private GatewayProblemWriter() {
    }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String code, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.empty();
        }
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String json = ProblemResponses.json(status, code, detail, CorrelationIdWebFilter.of(exchange));
        DataBuffer buffer = response.bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
