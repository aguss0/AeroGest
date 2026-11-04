package ar.edu.utn.frc.aerogest.flight;

import static ar.edu.utn.frc.aerogest.flight.TestAuth.admin;
import static ar.edu.utn.frc.aerogest.flight.TestAuth.service;
import static ar.edu.utn.frc.aerogest.flight.TestAuth.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.flight.opensky.AircraftState;
import ar.edu.utn.frc.aerogest.flight.opensky.OpenSkyException;
import ar.edu.utn.frc.aerogest.flight.service.DepartureVerificationService;
import ar.edu.utn.frc.aerogest.flight.service.DepartureVerificationService.RunSummary;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verificación de la salida real con OpenSky (RN-16) de punta a punta: API, regla, base real y
 * su efecto sobre la asignación de plazas. OpenSky se reemplaza por un doble.
 */
class DepartureVerificationIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private DepartureVerificationService verificationService;

    private Flight trackedFlight(String code, Instant departure, String callsign, String icao24) {
        Flight flight = Flight.create(code, "COR", "EZE", departure, departure.plus(Duration.ofHours(2)), 10,
                Instant.now());
        flight.assignAircraft(callsign, icao24, Instant.now());
        return flightRepository.saveAndFlush(flight);
    }

    private void openSkyReports(AircraftState... states) {
        when(openSky.statesOf(anyCollection())).thenReturn(List.of(states));
    }

    private static AircraftState inTheAir(String icao24, String callsign) {
        return new AircraftState(icao24, callsign, false, Instant.now());
    }

    private java.sql.Timestamp departureDetectedAt(Long id) {
        return jdbc.queryForObject("select departure_detected_at from flights where id = ?", java.sql.Timestamp.class, id);
    }

    private java.sql.Timestamp departureCheckedAt(Long id) {
        return jdbc.queryForObject("select departure_checked_at from flights where id = ?", java.sql.Timestamp.class, id);
    }

    private int allocate(Long flightId) throws Exception {
        return mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flightId).with(service())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    // ---------- alta con aeronave ----------

    @Test
    void elAdminCreaUnVueloConAeronaveYQuedaNormalizada() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"AG7001","origin":"COR","destination":"EZE","departureAt":"%s","arrivalAt":"%s",
                                 "capacity":10,"callsign":"arg1132","aircraftIcao24":"E0659A"}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.callsign").value("ARG1132"))
                .andExpect(jsonPath("$.aircraftIcao24").value("e0659a"));

        assertThat(jdbc.queryForObject("select aircraft_icao24 from flights where code = 'AG7001'", String.class))
                .isEqualTo("e0659a");
    }

    @Test
    void callsignSinIcao24SeRechaza_RN16() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"AG7002","origin":"COR","destination":"EZE","departureAt":"%s","arrivalAt":"%s",
                                 "capacity":10,"callsign":"ARG1132"}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AIRCRAFT_REFERENCE"));

        assertThat(flightCount()).isZero();
    }

    @Test
    void icao24ConFormatoInvalidoSeRechaza() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content("""
                                {"code":"AG7003","origin":"COR","destination":"EZE","departureAt":"%s","arrivalAt":"%s",
                                 "capacity":10,"callsign":"ARG1132","aircraftIcao24":"zz12"}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void modificarSinAeronaveLaQuita() throws Exception {
        Flight flight = trackedFlight("AG7004", DEPARTURE, "ARG1132", "e0659a");

        mockMvc.perform(put("/api/v1/flights/{id}", flight.getId()).with(admin()).contentType(APPLICATION_JSON)
                        .content("""
                                {"origin":"COR","destination":"EZE","departureAt":"%s","arrivalAt":"%s","capacity":10}
                                """.formatted(DEPARTURE, ARRIVAL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callsign").doesNotExist());

        assertThat(jdbc.queryForObject("select aircraft_icao24 from flights where id = ?", String.class, flight.getId()))
                .isNull();
    }

    // ---------- verificación manual (POST /departure-check) ----------

    @Test
    void siLaAeronaveEstaEnElAireConSuCallsignElVueloSeCierraYNoAceptaReservas_RN16() throws Exception {
        Flight flight = trackedFlight("AG7010", DEPARTURE, "ARG1132", "e0659a");
        assertThat(allocate(flight.getId())).isEqualTo(201);
        openSkyReports(inTheAir("e0659a", "ARG1132"));

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("DEPARTURE_DETECTED"))
                .andExpect(jsonPath("$.status").value("CLOSED"))
                .andExpect(jsonPath("$.observedCallsign").value("ARG1132"))
                .andExpect(jsonPath("$.observedOnGround").value(false))
                .andExpect(jsonPath("$.departureDetectedAt").exists());

        assertThat(flightStatus(flight.getId())).isEqualTo("CLOSED");
        assertThat(departureDetectedAt(flight.getId())).isNotNull();
        // La salida programada es futura, pero el vuelo ya no acepta reservas
        mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flight.getId()).with(service())
                        .contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_RESERVABLE"));
        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
    }

    @Test
    void siLaAeronaveEstaEnTierraElVueloSigueProgramado() throws Exception {
        Flight flight = trackedFlight("AG7011", DEPARTURE, "ARG1132", "e0659a");
        openSkyReports(new AircraftState("e0659a", "ARG1132", true, Instant.now()));

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ON_GROUND"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.departureCheckedAt").exists());

        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
        assertThat(departureCheckedAt(flight.getId())).isNotNull();
        assertThat(departureDetectedAt(flight.getId())).isNull();
        assertThat(allocate(flight.getId())).isEqualTo(201);
    }

    @Test
    void siLaAeronaveVuelaConOtroCallsignNoEsEsteVuelo() throws Exception {
        Flight flight = trackedFlight("AG7012", DEPARTURE, "ARG1132", "e0659a");
        openSkyReports(inTheAir("e0659a", "ARG1520"));

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("OTHER_FLIGHT"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));

        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
    }

    @Test
    void siOpenSkyNoTieneDatosDeLaAeronaveNoCambiaNada() throws Exception {
        Flight flight = trackedFlight("AG7013", DEPARTURE, "ARG1132", "e0659a");
        openSkyReports();

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("NOT_SEEN"))
                .andExpect(jsonPath("$.observedCallsign").doesNotExist());

        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
    }

    @Test
    void siOpenSkyNoRespondeATiempoDevuelve504YElVueloNoCambia() throws Exception {
        Flight flight = trackedFlight("AG7014", DEPARTURE, "ARG1132", "e0659a");
        when(openSky.statesOf(anyCollection()))
                .thenThrow(new OpenSkyException(ErrorCode.OPENSKY_TIMEOUT, "OpenSky no respondió a tiempo", null));

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("OPENSKY_TIMEOUT"));

        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
        assertThat(departureCheckedAt(flight.getId())).isNull();
        assertThat(allocate(flight.getId())).isEqualTo(201);
    }

    @Test
    void siOpenSkyRespondeAlgoIninteligibleDevuelve502YElVueloNoCambia() throws Exception {
        Flight flight = trackedFlight("AG7015", DEPARTURE, "ARG1132", "e0659a");
        when(openSky.statesOf(anyCollection())).thenThrow(
                new OpenSkyException(ErrorCode.OPENSKY_INVALID_RESPONSE, "Respuesta ilegible", null));

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("OPENSKY_INVALID_RESPONSE"));

        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
    }

    @Test
    void unVueloSinAeronaveNoSePuedeVerificar() throws Exception {
        Flight flight = saveFlight("AG7016", 10);

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_TRACKABLE"));

        verify(openSky, never()).statesOf(anyCollection());
    }

    @Test
    void unVueloInexistenteDevuelve404() throws Exception {
        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", 999_999).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
    }

    @Test
    void soloElAdminPuedeForzarLaVerificacion() throws Exception {
        Flight flight = trackedFlight("AG7017", DEPARTURE, "ARG1132", "e0659a");

        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()).with(user()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/flights/{id}/departure-check", flight.getId()))
                .andExpect(status().isUnauthorized());

        verify(openSky, never()).statesOf(anyCollection());
    }

    @Test
    void laRespuestaDelVueloIncluyeLosDatosDeLaVerificacion() throws Exception {
        Flight flight = trackedFlight("AG7018", DEPARTURE, "ARG1132", "e0659a");

        mockMvc.perform(get("/api/v1/flights/{id}", flight.getId()).with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.callsign").value("ARG1132"))
                .andExpect(jsonPath("$.aircraftIcao24").value("e0659a"))
                .andExpect(jsonPath("$.departureDetectedAt").doesNotExist());
    }

    // ---------- job ----------

    @SuppressWarnings("unchecked")
    @Test
    void elJobConsultaUnaSolaVezLosVuelosDeLaVentanaYCierraLosQueDespegaron() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Flight departed = trackedFlight("AG7020", now.plus(Duration.ofMinutes(30)), "ARG1132", "e0659a");
        Flight onGround = trackedFlight("AG7021", now.plus(Duration.ofMinutes(90)), "ARG1520", "e06442");
        Flight farAway = trackedFlight("AG7022", now.plus(Duration.ofDays(3)), "ARG1300", "e01234");
        Flight withoutAircraft = saveFlight("AG7023", 10);
        openSkyReports(inTheAir("e0659a", "ARG1132"), new AircraftState("e06442", "ARG1520", true, now));

        RunSummary summary = verificationService.verifyUpcoming();

        assertThat(summary.candidates()).isEqualTo(2);
        assertThat(summary.closed()).isEqualTo(1);
        ArgumentCaptor<Collection<String>> queried = ArgumentCaptor.forClass(Collection.class);
        verify(openSky).statesOf(queried.capture());
        assertThat(queried.getValue()).containsExactlyInAnyOrder("e0659a", "e06442");

        assertThat(flightStatus(departed.getId())).isEqualTo("CLOSED");
        assertThat(flightStatus(onGround.getId())).isEqualTo("SCHEDULED");
        assertThat(departureCheckedAt(onGround.getId())).isNotNull();
        assertThat(flightStatus(farAway.getId())).isEqualTo("SCHEDULED");
        assertThat(departureCheckedAt(farAway.getId())).isNull();
        assertThat(flightStatus(withoutAircraft.getId())).isEqualTo("SCHEDULED");
    }

    @Test
    void siOpenSkyFallaElJobNoCambiaNingunVuelo() throws Exception {
        Instant now = Instant.now();
        Flight flight = trackedFlight("AG7030", now.plus(Duration.ofMinutes(30)), "ARG1132", "e0659a");
        when(openSky.statesOf(anyCollection()))
                .thenThrow(new OpenSkyException(ErrorCode.OPENSKY_UNAVAILABLE, "caído", null));

        RunSummary summary = verificationService.verifyUpcoming();

        assertThat(summary.closed()).isZero();
        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
        assertThat(departureCheckedAt(flight.getId())).isNull();
        assertThat(allocate(flight.getId())).isEqualTo(201);
    }

    @Test
    void sinVuelosEnLaVentanaNoSeGastanCreditos() {
        trackedFlight("AG7040", DEPARTURE, "ARG1132", "e0659a");

        RunSummary summary = verificationService.verifyUpcoming();

        assertThat(summary.candidates()).isZero();
        verify(openSky, never()).statesOf(anyCollection());
    }
}
