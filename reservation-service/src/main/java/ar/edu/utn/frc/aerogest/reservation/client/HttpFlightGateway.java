package ar.edu.utn.frc.aerogest.reservation.client;

import ar.edu.utn.frc.aerogest.common.web.CorrelationId;
import ar.edu.utn.frc.aerogest.reservation.config.FlightServiceProperties;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Implementación HTTP de {@link FlightGateway} sobre los endpoints /internal de Flight Service. */
@Component
public class HttpFlightGateway implements FlightGateway {

    private static final Logger log = LoggerFactory.getLogger(HttpFlightGateway.class);

    private final HttpClient httpClient;
    private final ServiceTokenProvider tokenProvider;
    private final String baseUrl;
    private final Duration readTimeout;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public HttpFlightGateway(HttpClient httpClient, ServiceTokenProvider tokenProvider,
                             FlightServiceProperties properties) {
        this.httpClient = httpClient;
        this.tokenProvider = tokenProvider;
        String url = properties.baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.readTimeout = Duration.ofMillis(properties.readTimeoutMs());
    }

    @Override
    public AllocationOutcome allocateSeat(long flightId, UUID reservationId) {
        String body = "{\"reservationId\":\"" + reservationId + "\"}";
        HttpResponse<String> response = send("POST", flightId, "/seat-allocations", body);
        int status = response.statusCode();
        if (status == 201 || status == 200) {
            return parseAllocation(response.body(), flightId, reservationId, status == 201);
        }
        throw translateError(status, response.body());
    }

    @Override
    public void releaseSeat(long flightId, UUID reservationId) {
        HttpResponse<String> response = send("DELETE", flightId, "/seat-allocations/" + reservationId, null);
        int status = response.statusCode();
        if (status == 204 || status == 200 || status == 404) {
            return; // 404: el vuelo no existe, no hay nada que liberar
        }
        throw translateError(status, response.body());
    }

    // ---------------------------------------------------------------- HTTP

    private HttpResponse<String> send(String method, long flightId, String suffix, String body) {
        HttpResponse<String> response = doSend(method, flightId, suffix, body);
        if (response.statusCode() == 401) {
            // Posible token vencido o revocado: se pide uno nuevo y se reintenta UNA vez
            tokenProvider.invalidate();
            response = doSend(method, flightId, suffix, body);
        }
        return response;
    }

    private HttpResponse<String> doSend(String method, long flightId, String suffix, String body) {
        String token = tokenProvider.getToken();
        HttpRequest.Builder builder = HttpRequest
                .newBuilder(URI.create(baseUrl + "/internal/v1/flights/" + flightId + suffix))
                .timeout(readTimeout)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/json");
        String correlationId = MDC.get(CorrelationId.MDC_KEY);
        if (correlationId != null && !correlationId.isBlank()) {
            builder.header(CorrelationId.HEADER, correlationId);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        try {
            return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            log.warn("Timeout de conexión con Flight Service ({} {})", method, suffix);
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "No se pudo conectar con Flight Service", e);
        } catch (HttpTimeoutException e) {
            log.warn("Flight Service no respondió a tiempo ({} {})", method, suffix);
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_TIMEOUT,
                    "Flight Service no respondió a tiempo", e);
        } catch (IOException e) {
            log.warn("Error de comunicación con Flight Service ({} {}): {}", method, suffix, e.toString());
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Flight Service no está disponible", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Operación interrumpida", e);
        }
    }

    // ------------------------------------------------------------ Respuestas

    private AllocationOutcome parseAllocation(String raw, long flightId, UUID reservationId, boolean created) {
        try {
            JsonNode node = mapper.readTree(raw);
            JsonNode idNode = node.path("reservationId");
            JsonNode available = node.path("availableSeats");
            if (!idNode.isString() || !UUID.fromString(idNode.asString()).equals(reservationId)
                    || !available.isNumber()
                    || !"ALLOCATED".equals(node.path("status").asString(""))) {
                throw invalidResponse("La asignación devuelta por Flight no corresponde a la reserva", null);
            }
            return new AllocationOutcome(reservationId, flightId, created, available.asInt());
        } catch (FlightServiceException e) {
            throw e;
        } catch (RuntimeException e) {
            throw invalidResponse("Respuesta ilegible de Flight Service", e);
        }
    }

    private RuntimeException translateError(int status, String raw) {
        String code = extractCode(raw);
        if (status == 404 && "FLIGHT_NOT_FOUND".equals(code)) {
            return new BusinessRuleException(ErrorCode.FLIGHT_NOT_FOUND, "El vuelo indicado no existe");
        }
        if (status == 409) {
            return switch (code) {
                case "NO_AVAILABILITY" -> new BusinessRuleException(ErrorCode.NO_AVAILABILITY,
                        "El vuelo no tiene plazas disponibles");
                case "FLIGHT_NOT_RESERVABLE" -> new BusinessRuleException(ErrorCode.FLIGHT_NOT_RESERVABLE,
                        "El vuelo no admite reservas (cerrado, cancelado o ya partió)");
                default -> new BusinessRuleException(ErrorCode.ALLOCATION_CONFLICT,
                        "Conflicto al asignar la plaza en el vuelo");
            };
        }
        if (status == 401 || status == 403) {
            log.error("Flight Service rechazó la credencial de servicio (HTTP {})", status);
            return new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Flight Service rechazó la credencial de servicio");
        }
        if (status >= 500) {
            log.warn("Flight Service respondió HTTP {}", status);
            return new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Flight Service respondió con un error (" + status + ")");
        }
        return invalidResponse("Respuesta inesperada de Flight Service (HTTP " + status + ")", null);
    }

    private String extractCode(String raw) {
        try {
            return mapper.readTree(raw).path("code").asString("");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private FlightServiceException invalidResponse(String message, Throwable cause) {
        return cause == null
                ? new FlightServiceException(ErrorCode.INVALID_FLIGHT_RESPONSE, message)
                : new FlightServiceException(ErrorCode.INVALID_FLIGHT_RESPONSE, message, cause);
    }
}
