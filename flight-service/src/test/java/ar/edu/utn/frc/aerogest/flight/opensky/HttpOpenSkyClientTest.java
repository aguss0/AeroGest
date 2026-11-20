package ar.edu.utn.frc.aerogest.flight.opensky;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** El cliente de OpenSky contra un servidor falso: formato de la consulta, interpretación y fallas. */
class HttpOpenSkyClientTest {

    private FakeOpenSkyServer server;
    private HttpOpenSkyClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new FakeOpenSkyServer();
        // Margen amplio: con la máquina cargada (Keycloak, Testcontainers) 1 s llegó a dar un falso
        // OPENSKY_TIMEOUT. El caso de timeout real usa su propio cliente de 300 ms.
        client = clientFor(server.baseUrl(), 5000);
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private static HttpOpenSkyClient clientFor(String baseUrl, int readTimeoutMs) {
        OpenSkyProperties props = new OpenSkyProperties(true, baseUrl + "/", 500, readTimeoutMs,
                null, null, null, null);
        return new HttpOpenSkyClient(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build(), props);
    }

    /** Una fila de "states" con los 17 campos posicionales de OpenSky. */
    private static String row(String icao24, String callsign, boolean onGround) {
        String cs = callsign == null ? "null" : "\"" + callsign + "\"";
        return "[\"" + icao24 + "\"," + cs + ",\"Argentina\",1762250000,1762250005,-58.5,-34.8,10972.8,"
                + onGround + ",230.1,45.0,0.0,null,11277.6,\"1234\",false,0]";
    }

    private static String body(String... rows) {
        return "{\"time\":1762250010,\"states\":[" + String.join(",", rows) + "]}";
    }

    // ---------------------------------------------------------------- éxito

    @Test
    void consultaPorIcao24EnUnaSolaSolicitudEInterpretaLosEstados() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200,
                body(row("e0659a", "ARG1132 ", false), row("e06442", "ARG1520", true))));

        List<AircraftState> states = client.statesOf(List.of("E0659A", "e06442"));

        assertThat(server.received()).hasSize(1);
        assertThat(server.received().getFirst().path()).isEqualTo("/api/states/all");
        assertThat(server.received().getFirst().query()).isEqualTo("icao24=e0659a&icao24=e06442");
        assertThat(server.received().getFirst().userAgent()).isEqualTo("AeroGest/1.0");
        assertThat(states).containsExactly(
                new AircraftState("e0659a", "ARG1132", false, Instant.ofEpochSecond(1762250005)),
                new AircraftState("e06442", "ARG1520", true, Instant.ofEpochSecond(1762250005)));
    }

    @Test
    void sinAeronavesTransmitiendoOpenSkyDevuelveStatesNull() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200, "{\"time\":1762250010,\"states\":null}"));

        assertThat(client.statesOf(List.of("e0659a"))).isEmpty();
    }

    @Test
    void unCallsignVacioSeInterpretaComoAusente() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200, body(row("e0659a", "        ", false))));

        assertThat(client.statesOf(List.of("e0659a")).getFirst().callsign()).isNull();
    }

    @Test
    void sinIcao24NoConsulta() {
        assertThat(client.statesOf(List.of())).isEmpty();
        assertThat(server.received()).isEmpty();
    }

    // ---------------------------------------------------------------- fallas

    @Test
    void creditosAgotados429ConTiempoDeEspera() {
        server.respondWith(new FakeOpenSkyServer.Reply(429, "Too many requests",
                Map.of(HttpOpenSkyClient.RETRY_AFTER_HEADER, "1800"), 0));

        assertThatThrownBy(() -> client.statesOf(List.of("e0659a")))
                .isInstanceOfSatisfying(OpenSkyException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENSKY_RATE_LIMITED);
                    assertThat(e.getRetryAfter()).isEqualTo(Duration.ofMinutes(30));
                });
    }

    @Test
    void errorDelServidorEsNoDisponible() {
        server.respondWith(FakeOpenSkyServer.Reply.of(503, "{}"));

        assertFailure(ErrorCode.OPENSKY_UNAVAILABLE);
    }

    @Test
    void sinRespuestaATiempoEsTimeout() {
        server.respondWith(new FakeOpenSkyServer.Reply(200, body(), Map.of(), 2000));
        client = clientFor(server.baseUrl(), 300);

        assertFailure(ErrorCode.OPENSKY_TIMEOUT);
    }

    @Test
    void servidorInaccesibleEsNoDisponible() throws Exception {
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        client = clientFor("http://127.0.0.1:" + freePort + "/api", 500);

        assertFailure(ErrorCode.OPENSKY_UNAVAILABLE);
    }

    @Test
    void cuerpoQueNoEsJsonEsRespuestaInvalida() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200, "<html>mantenimiento</html>"));

        assertFailure(ErrorCode.OPENSKY_INVALID_RESPONSE);
    }

    @Test
    void statesQueNoEsListaEsRespuestaInvalida() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200, "{\"states\":\"x\"}"));

        assertFailure(ErrorCode.OPENSKY_INVALID_RESPONSE);
    }

    @Test
    void filaConOnGroundInvalidoInvalidaTodaLaRespuesta() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200,
                "{\"states\":[[\"e0659a\",\"ARG1132\",\"AR\",1,2,0,0,0,\"no\"]]}"));

        assertFailure(ErrorCode.OPENSKY_INVALID_RESPONSE);
    }

    @Test
    void filaIncompletaInvalidaTodaLaRespuesta() {
        server.respondWith(FakeOpenSkyServer.Reply.of(200, "{\"states\":[[\"e0659a\",\"ARG1132\"]]}"));

        assertFailure(ErrorCode.OPENSKY_INVALID_RESPONSE);
    }

    private void assertFailure(ErrorCode expected) {
        assertThatThrownBy(() -> client.statesOf(List.of("e0659a")))
                .isInstanceOfSatisfying(OpenSkyException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }
}
