package ar.edu.utn.frc.aerogest.gateway.config;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * Errores que se originan en el gateway mismo, con el formato común:
 *  - 503 UPSTREAM_UNAVAILABLE: el servicio de destino no acepta conexiones (caído o sin resolver).
 *  - 504 UPSTREAM_TIMEOUT: el servicio de destino no respondió dentro del response-timeout.
 *  - 404 ROUTE_NOT_FOUND: ninguna ruta publicada coincide.
 *
 * Los errores que devuelve un servicio (409, 404 de negocio, 503 de Reservation cuando Flight
 * está caído, etc.) no pasan por acá: el gateway los reenvía tal cual.
 * Va antes que el manejador por defecto de Spring Boot (orden -1).
 */
@Component
@Order(-2)
public class GatewayErrorHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayErrorHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (hasCause(ex, ConnectException.class) || hasCause(ex, UnknownHostException.class)
                || hasCause(ex, io.netty.channel.ConnectTimeoutException.class)) {
            log.warn("Servicio de destino no disponible: {}", ex.getMessage());
            return GatewayProblemWriter.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "UPSTREAM_UNAVAILABLE",
                    "El servicio no está disponible en este momento. Reintentá más tarde");
        }
        HttpStatusCode status = ex instanceof ResponseStatusException rse ? rse.getStatusCode() : null;
        if (hasCause(ex, TimeoutException.class) || (status != null && status.value() == 504)) {
            log.warn("El servicio de destino no respondió a tiempo: {}", ex.getMessage());
            return GatewayProblemWriter.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "UPSTREAM_TIMEOUT",
                    "El servicio no respondió a tiempo. Consultá el estado antes de reintentar");
        }
        if (status != null && status.value() == 404) {
            return GatewayProblemWriter.write(exchange, HttpStatus.NOT_FOUND, "ROUTE_NOT_FOUND",
                    "No existe la ruta solicitada");
        }
        if (status != null && status.is4xxClientError()) {
            HttpStatus resolved = HttpStatus.valueOf(status.value());
            return GatewayProblemWriter.write(exchange, resolved, "BAD_REQUEST", resolved.getReasonPhrase());
        }
        log.error("Error inesperado en el gateway", ex);
        return GatewayProblemWriter.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Ocurrió un error inesperado");
    }

    private static boolean hasCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }
}
