package ar.edu.utn.frc.aerogest.reservation.client;

import ar.edu.utn.frc.aerogest.reservation.config.FlightServiceProperties;
import ar.edu.utn.frc.aerogest.reservation.config.KeycloakClientProperties;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Obtiene (y cachea) el token de servicio de Reservation Service con el flujo client_credentials
 * de Keycloak. Se renueva cuando faltan menos de {@link #SKEW} para que venza.
 */
@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);
    static final Duration SKEW = Duration.ofSeconds(30);

    private final HttpClient httpClient;
    private final KeycloakClientProperties keycloak;
    private final Duration requestTimeout;
    private final Clock clock;
    private final JsonMapper mapper = JsonMapper.builder().build();

    private String token;
    private Instant refreshAt = Instant.MIN;

    public ServiceTokenProvider(HttpClient httpClient, KeycloakClientProperties keycloak,
                                FlightServiceProperties flight, Clock clock) {
        this.httpClient = httpClient;
        this.keycloak = keycloak;
        this.requestTimeout = Duration.ofMillis(flight.readTimeoutMs());
        this.clock = clock;
    }

    public synchronized String getToken() {
        if (token == null || !clock.instant().isBefore(refreshAt)) {
            fetch();
        }
        return token;
    }

    /** Descarta el token cacheado (p. ej. si Flight lo rechazó con 401). */
    public synchronized void invalidate() {
        token = null;
        refreshAt = Instant.MIN;
    }

    private void fetch() {
        String form = "grant_type=client_credentials"
                + "&client_id=" + enc(keycloak.clientId())
                + "&client_secret=" + enc(keycloak.clientSecret());
        HttpRequest request = HttpRequest.newBuilder(URI.create(keycloak.tokenUri()))
                .timeout(requestTimeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.error("Keycloak rechazó el pedido de token de servicio: HTTP {}", response.statusCode());
                throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                        "No se pudo obtener la credencial para comunicarse con Flight Service");
            }
            JsonNode body = mapper.readTree(response.body());
            String accessToken = body.path("access_token").asString("");
            long expiresIn = body.path("expires_in").asLong(0);
            if (accessToken.isBlank() || expiresIn <= 0) {
                throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                        "Respuesta de token inválida");
            }
            token = accessToken;
            refreshAt = clock.instant().plusSeconds(expiresIn).minus(SKEW);
        } catch (FlightServiceException e) {
            throw e;
        } catch (HttpTimeoutException e) {
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Timeout al obtener la credencial de servicio", e);
        } catch (IOException e) {
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "No se pudo contactar al proveedor de identidad", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Operación interrumpida", e);
        } catch (RuntimeException e) {
            throw new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE,
                    "Respuesta de token ilegible", e);
        }
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
