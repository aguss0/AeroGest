package ar.edu.utn.frc.aerogest.reservation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.utn.frc.aerogest.reservation.config.FlightServiceProperties;
import ar.edu.utn.frc.aerogest.reservation.config.KeycloakClientProperties;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

/** Prueba el cliente contra servidores falsos: Keycloak y Flight, sin red real. */
class HttpFlightGatewayTest {

    private static final UUID RESERVATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private FakeHttpServer keycloak;
    private FakeHttpServer flight;
    private HttpFlightGateway gateway;
    private final AtomicInteger tokens = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        keycloak = new FakeHttpServer();
        flight = new FakeHttpServer();
        keycloak.respondWith(r -> FakeHttpServer.Reply.of(200,
                "{\"access_token\":\"tok-" + tokens.incrementAndGet() + "\",\"expires_in\":300}"));
        gateway = gatewayFor(flight.baseUrl(), 1000);
    }

    private HttpFlightGateway gatewayFor(String flightUrl, int readTimeoutMs) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        FlightServiceProperties props = new FlightServiceProperties(flightUrl + "/", 500, readTimeoutMs);
        ServiceTokenProvider provider = new ServiceTokenProvider(client,
                new KeycloakClientProperties(keycloak.baseUrl() + "/token", "reservation-service", "s"),
                props, Clock.systemUTC());
        return new HttpFlightGateway(client, provider, props);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        keycloak.close();
        flight.close();
    }

    private static String allocationJson(UUID id, String status, int available) {
        return "{\"reservationId\":\"" + id + "\",\"flightId\":7,\"status\":\"" + status
                + "\",\"availableSeats\":" + available + "}";
    }

    private static String error(String code) {
        return "{\"status\":409,\"code\":\"" + code + "\",\"detail\":\"x\"}";
    }

    private void assertBusiness(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isNotInstanceOf(FlightServiceException.class)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    private void assertUncertain(Runnable call, ErrorCode expected) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(FlightServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------------------------------------------------------------- allocate

    @Test
    void asignaUnaPlazaNueva() {
        flight.respondWith(FakeHttpServer.Reply.of(201, allocationJson(RESERVATION, "ALLOCATED", 41)));

        AllocationOutcome outcome = gateway.allocateSeat(7, RESERVATION);

        assertThat(outcome.created()).isTrue();
        assertThat(outcome.availableSeats()).isEqualTo(41);
        assertThat(outcome.flightId()).isEqualTo(7);
        FakeHttpServer.Received sent = flight.received().get(0);
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.path()).isEqualTo("/internal/v1/flights/7/seat-allocations");
        assertThat(sent.body()).contains(RESERVATION.toString());
        assertThat(sent.headers().getFirst("Authorization")).isEqualTo("Bearer tok-1");
    }

    @Test
    void unReintentoYaProcesadoSeInformaComoNoCreado() {
        flight.respondWith(FakeHttpServer.Reply.of(200, allocationJson(RESERVATION, "ALLOCATED", 40)));

        assertThat(gateway.allocateSeat(7, RESERVATION).created()).isFalse();
    }

    @Test
    void propagaElIdDeCorrelacion() {
        flight.respondWith(FakeHttpServer.Reply.of(201, allocationJson(RESERVATION, "ALLOCATED", 1)));
        MDC.put("correlationId", "abc-123");

        gateway.allocateSeat(7, RESERVATION);

        assertThat(flight.received().get(0).headers().getFirst("X-Correlation-Id")).isEqualTo("abc-123");
    }

    @Test
    void reusaElTokenEntreLlamadas() {
        flight.respondWith(FakeHttpServer.Reply.of(201, allocationJson(RESERVATION, "ALLOCATED", 1)));

        gateway.allocateSeat(7, RESERVATION);
        gateway.allocateSeat(7, RESERVATION);

        assertThat(keycloak.received()).hasSize(1);
    }

    @Test
    void vueloInexistente() {
        flight.respondWith(FakeHttpServer.Reply.of(404, "{\"status\":404,\"code\":\"FLIGHT_NOT_FOUND\"}"));

        assertBusiness(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_NOT_FOUND);
    }

    @Test
    void sinPlazas() {
        flight.respondWith(FakeHttpServer.Reply.of(409, error("NO_AVAILABILITY")));

        assertBusiness(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.NO_AVAILABILITY);
    }

    @Test
    void vueloNoReservable() {
        flight.respondWith(FakeHttpServer.Reply.of(409, error("FLIGHT_NOT_RESERVABLE")));

        assertBusiness(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_NOT_RESERVABLE);
    }

    @Test
    void conflictoDeAsignacion() {
        flight.respondWith(FakeHttpServer.Reply.of(409, error("ALLOCATION_CONFLICT")));

        assertBusiness(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.ALLOCATION_CONFLICT);
    }

    @Test
    void conflictoConCodigoDesconocidoSeTrataComoConflicto() {
        flight.respondWith(FakeHttpServer.Reply.of(409, "no es json"));

        assertBusiness(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.ALLOCATION_CONFLICT);
    }

    @Test
    void errorDelServidorEsResultadoIncierto() {
        flight.respondWith(FakeHttpServer.Reply.of(500, "{\"code\":\"INTERNAL_ERROR\"}"));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
    }

    @Test
    void servicioNoDisponible503() {
        flight.respondWith(FakeHttpServer.Reply.of(503, ""));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
    }

    @Test
    void conexionRechazadaCuandoFlightEstaCaido() {
        String deadUrl = flight.baseUrl();
        flight.close();
        HttpFlightGateway down = gatewayFor(deadUrl, 1000);

        assertUncertain(() -> down.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
    }

    @Test
    void flightQueTardaDemasiadoProvocaTimeout() {
        flight.respondWith(FakeHttpServer.Reply.slow(201, allocationJson(RESERVATION, "ALLOCATED", 1), 1500));
        HttpFlightGateway impatient = gatewayFor(flight.baseUrl(), 300);

        assertUncertain(() -> impatient.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_TIMEOUT);
    }

    @Test
    void cuerpoQueNoEsJsonEsRespuestaInvalida() {
        flight.respondWith(FakeHttpServer.Reply.of(201, "<html>"));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.INVALID_FLIGHT_RESPONSE);
    }

    @Test
    void respuestaDeOtraReservaEsInvalida() {
        flight.respondWith(FakeHttpServer.Reply.of(201, allocationJson(UUID.randomUUID(), "ALLOCATED", 1)));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.INVALID_FLIGHT_RESPONSE);
    }

    @Test
    void asignacionQueVieneLiberadaEsInvalida() {
        flight.respondWith(FakeHttpServer.Reply.of(201, allocationJson(RESERVATION, "RELEASED", 1)));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.INVALID_FLIGHT_RESPONSE);
    }

    @Test
    void respuestaSinDisponibilidadEsInvalida() {
        flight.respondWith(FakeHttpServer.Reply.of(201,
                "{\"reservationId\":\"" + RESERVATION + "\",\"status\":\"ALLOCATED\"}"));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.INVALID_FLIGHT_RESPONSE);
    }

    @Test
    void statusInesperado400EsRespuestaInvalida() {
        flight.respondWith(FakeHttpServer.Reply.of(400, "{\"code\":\"VALIDATION_ERROR\"}"));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.INVALID_FLIGHT_RESPONSE);
    }

    // -------------------------------------------------------------- 401 / 403

    @Test
    void conUn401PideTokenNuevoYReintentaUnaVez() {
        AtomicInteger calls = new AtomicInteger();
        flight.respondWith(r -> calls.incrementAndGet() == 1
                ? FakeHttpServer.Reply.of(401, "")
                : FakeHttpServer.Reply.of(201, allocationJson(RESERVATION, "ALLOCATED", 3)));

        AllocationOutcome outcome = gateway.allocateSeat(7, RESERVATION);

        assertThat(outcome.created()).isTrue();
        assertThat(keycloak.received()).hasSize(2);
        assertThat(flight.received().get(0).headers().getFirst("Authorization")).isEqualTo("Bearer tok-1");
        assertThat(flight.received().get(1).headers().getFirst("Authorization")).isEqualTo("Bearer tok-2");
    }

    @Test
    void siElSegundoIntentoTambienDa401NoReintentaMas() {
        flight.respondWith(FakeHttpServer.Reply.of(401, ""));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
        assertThat(flight.received()).hasSize(2);
    }

    @Test
    void un403NoSeReintenta() {
        flight.respondWith(FakeHttpServer.Reply.of(403, ""));

        assertUncertain(() -> gateway.allocateSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
        assertThat(flight.received()).hasSize(1);
    }

    // ----------------------------------------------------------------- release

    @Test
    void liberaUnaPlaza() {
        flight.respondWith(FakeHttpServer.Reply.of(204, ""));

        gateway.releaseSeat(7, RESERVATION);

        FakeHttpServer.Received sent = flight.received().get(0);
        assertThat(sent.method()).isEqualTo("DELETE");
        assertThat(sent.path()).isEqualTo("/internal/v1/flights/7/seat-allocations/" + RESERVATION);
        assertThat(sent.headers().getFirst("Authorization")).isEqualTo("Bearer tok-1");
    }

    @Test
    void liberarUnVueloInexistenteNoFalla() {
        flight.respondWith(FakeHttpServer.Reply.of(404, "{\"code\":\"FLIGHT_NOT_FOUND\"}"));

        assertThatCode(() -> gateway.releaseSeat(7, RESERVATION)).doesNotThrowAnyException();
    }

    @Test
    void liberarConFlightCaidoEsResultadoIncierto() {
        flight.respondWith(FakeHttpServer.Reply.of(500, ""));

        assertUncertain(() -> gateway.releaseSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
    }

    @Test
    void liberarConTimeout() {
        flight.respondWith(FakeHttpServer.Reply.slow(204, "", 1500));
        HttpFlightGateway impatient = gatewayFor(flight.baseUrl(), 300);

        assertUncertain(() -> impatient.releaseSeat(7, RESERVATION), ErrorCode.FLIGHT_SERVICE_TIMEOUT);
    }
}
