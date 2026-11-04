package ar.edu.utn.frc.aerogest.flight.opensky;

import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Cliente HTTP de OpenSky Network: {@code GET /states/all?icao24=..&icao24=..} (acceso anónimo).
 *
 * Se consulta por icao24 porque la API no filtra por callsign. Cada fila de {@code states} es
 * un arreglo posicional: [0] icao24, [1] callsign (con espacios a la derecha), [4] last_contact
 * (segundos Unix) y [8] on_ground. Si la respuesta no respeta ese formato se rechaza entera:
 * nunca se infiere un despegue de datos dudosos.
 */
public class HttpOpenSkyClient implements OpenSkyClient {

    static final String RETRY_AFTER_HEADER = "X-Rate-Limit-Retry-After-Seconds";

    private static final Logger log = LoggerFactory.getLogger(HttpOpenSkyClient.class);
    private static final int MIN_FIELDS = 9;

    private final HttpClient httpClient;
    private final String baseUrl;
    private final Duration readTimeout;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public HttpOpenSkyClient(HttpClient httpClient, OpenSkyProperties properties) {
        this.httpClient = httpClient;
        String url = properties.baseUrl();
        this.baseUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.readTimeout = Duration.ofMillis(properties.readTimeoutMs());
    }

    @Override
    public List<AircraftState> statesOf(Collection<String> icao24s) {
        if (icao24s.isEmpty()) {
            return List.of();
        }
        String query = icao24s.stream()
                .map(id -> "icao24=" + URLEncoder.encode(id.toLowerCase(Locale.ROOT), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/states/all?" + query))
                .timeout(readTimeout)
                .header("Accept", "application/json")
                .header("User-Agent", "AeroGest/1.0")
                .GET()
                .build();

        HttpResponse<String> response = send(request);
        int status = response.statusCode();
        if (status == 429) {
            Duration retryAfter = response.headers().firstValue(RETRY_AFTER_HEADER)
                    .flatMap(HttpOpenSkyClient::parseSeconds).orElse(null);
            throw new OpenSkyException(ErrorCode.OPENSKY_RATE_LIMITED,
                    "Se agotaron los créditos de OpenSky", retryAfter, null);
        }
        if (status != 200) {
            throw new OpenSkyException(ErrorCode.OPENSKY_UNAVAILABLE,
                    "OpenSky respondió con estado " + status, null);
        }
        return parse(response.body());
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpConnectTimeoutException e) {
            throw new OpenSkyException(ErrorCode.OPENSKY_UNAVAILABLE, "No se pudo conectar con OpenSky", e);
        } catch (HttpTimeoutException e) {
            throw new OpenSkyException(ErrorCode.OPENSKY_TIMEOUT, "OpenSky no respondió a tiempo", e);
        } catch (IOException e) {
            throw new OpenSkyException(ErrorCode.OPENSKY_UNAVAILABLE, "OpenSky no está disponible: " + e, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OpenSkyException(ErrorCode.OPENSKY_UNAVAILABLE, "Consulta a OpenSky interrumpida", e);
        }
    }

    List<AircraftState> parse(String raw) {
        JsonNode root;
        try {
            root = mapper.readTree(raw);
        } catch (RuntimeException e) {
            throw invalid("OpenSky devolvió un cuerpo que no es JSON", e);
        }
        if (root == null || !root.isObject()) {
            throw invalid("OpenSky devolvió un JSON que no es un objeto", null);
        }
        JsonNode states = root.path("states");
        if (states.isMissingNode() || states.isNull()) {
            return List.of();   // ninguna de las aeronaves está transmitiendo
        }
        if (!states.isArray()) {
            throw invalid("El campo 'states' de OpenSky no es una lista", null);
        }
        List<AircraftState> result = new ArrayList<>();
        for (JsonNode row : states) {
            result.add(parseRow(row));
        }
        return result;
    }

    private AircraftState parseRow(JsonNode row) {
        if (!row.isArray() || row.size() < MIN_FIELDS) {
            throw invalid("Fila de 'states' con formato inesperado", null);
        }
        JsonNode icao24 = row.get(0);
        JsonNode callsign = row.get(1);
        JsonNode lastContact = row.get(4);
        JsonNode onGround = row.get(8);
        if (!icao24.isString() || !icao24.asString().matches("^[0-9a-fA-F]{6}$")) {
            throw invalid("icao24 inválido en la respuesta de OpenSky", null);
        }
        if (!(callsign.isNull() || callsign.isString())) {
            throw invalid("callsign inválido en la respuesta de OpenSky", null);
        }
        if (!onGround.isBoolean()) {
            throw invalid("on_ground inválido en la respuesta de OpenSky", null);
        }
        String cleanCallsign = callsign.isNull() ? null : callsign.asString().trim().toUpperCase(Locale.ROOT);
        Instant contact = lastContact.isNumber() ? Instant.ofEpochSecond(lastContact.asLong()) : null;
        return new AircraftState(icao24.asString().toLowerCase(Locale.ROOT),
                cleanCallsign == null || cleanCallsign.isEmpty() ? null : cleanCallsign,
                onGround.asBoolean(), contact);
    }

    private static OpenSkyException invalid(String message, Throwable cause) {
        log.warn("Respuesta inválida de OpenSky: {}", message);
        return new OpenSkyException(ErrorCode.OPENSKY_INVALID_RESPONSE, message, cause);
    }

    private static java.util.Optional<Duration> parseSeconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds > 0 ? java.util.Optional.of(Duration.ofSeconds(seconds)) : java.util.Optional.empty();
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }
}
