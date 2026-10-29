package ar.edu.utn.frc.aerogest.flight;

import static ar.edu.utn.frc.aerogest.flight.TestAuth.admin;
import static ar.edu.utn.frc.aerogest.flight.TestAuth.service;
import static ar.edu.utn.frc.aerogest.flight.TestAuth.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * API de vuelos de punta a punta (seguridad + validación + reglas + base real).
 * Cada prueba comprueba la respuesta HTTP y su efecto en la base de datos.
 */
class FlightApiIntegrationTest extends AbstractIntegrationTest {

    private String createBody(String code, String origin, String destination,
                              Instant departure, Instant arrival, Object capacity) {
        return """
                {"code":"%s","origin":"%s","destination":"%s","departureAt":"%s","arrivalAt":"%s","capacity":%s}
                """.formatted(code, origin, destination, departure, arrival, capacity);
    }

    private String updateBody(String origin, String destination, int capacity) {
        return """
                {"origin":"%s","destination":"%s","departureAt":"%s","arrivalAt":"%s","capacity":%d}
                """.formatted(origin, destination, DEPARTURE, ARRIVAL, capacity);
    }

    // ---------- seguridad (RN-09) ----------

    @Test
    void sinCredencialesDevuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/flights"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void unUsuarioSinPermisoNoPuedeCrearVuelos_403() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(user()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2001", "COR", "EZE", DEPARTURE, ARRIVAL, 100)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));

        assertThat(flightCount()).isZero();
    }

    @Test
    void unUsuarioPuedeConsultarVuelos() throws Exception {
        saveFlight("AG2002", 100);

        mockMvc.perform(get("/api/v1/flights").with(user()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void losEndpointsInternosSoloAdmitenElRolService() throws Exception {
        Flight flight = saveFlight("AG2003", 100);
        String path = "/internal/v1/flights/" + flight.getId() + "/availability";

        mockMvc.perform(get(path).with(user())).andExpect(status().isForbidden());
        mockMvc.perform(get(path).with(admin())).andExpect(status().isForbidden());
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
        mockMvc.perform(get(path).with(service()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acceptsReservations").value(true))
                .andExpect(jsonPath("$.availableSeats").value(100));
    }

    // ---------- alta de vuelos ----------

    @Test
    void elAdminCreaUnVueloYQuedaPersistido() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2010", "COR", "EZE", DEPARTURE, ARRIVAL, 100)))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.code").value("AG2010"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.availableSeats").value(100));

        assertThat(flightCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select seats_taken from flights where code = 'AG2010'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select status from flights where code = 'AG2010'", String.class))
                .isEqualTo("SCHEDULED");
    }

    @Test
    void rechazaUnCodigoDuplicado_RN01() throws Exception {
        saveFlight("AG2011", 100);

        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2011", "COR", "AEP", DEPARTURE, ARRIVAL, 50)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_FLIGHT_CODE"));

        assertThat(flightCount()).isEqualTo(1);
    }

    @Test
    void rechazaHorariosInvalidos_RN02() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2012", "COR", "EZE", DEPARTURE, DEPARTURE.minusSeconds(3600), 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SCHEDULE"));

        assertThat(flightCount()).isZero();
    }

    @Test
    void rechazaCapacidadInvalida_RN03() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2013", "COR", "EZE", DEPARTURE, ARRIVAL, 0)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[0].field").value("capacity"));

        assertThat(flightCount()).isZero();
    }

    @Test
    void rechazaOrigenIgualADestino_RN11() throws Exception {
        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2014", "COR", "COR", DEPARTURE, ARRIVAL, 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROUTE"));
    }

    @Test
    void rechazaUnaSalidaEnElPasado_RN12() throws Exception {
        Instant past = Instant.now().minus(Duration.ofDays(1));

        mockMvc.perform(post("/api/v1/flights").with(admin()).contentType(APPLICATION_JSON)
                        .content(createBody("AG2015", "COR", "EZE", past, past.plusSeconds(7200), 100)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("DEPARTURE_IN_PAST"));
    }

    // ---------- consulta ----------

    @Test
    void consultarUnVueloInexistenteDevuelve404() throws Exception {
        mockMvc.perform(get("/api/v1/flights/999999").with(user()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
    }

    @Test
    void laBusquedaFiltraPorOrigenDestinoYEstado() throws Exception {
        saveFlight("AG2020", "COR", "EZE", 100);
        saveFlight("AG2021", "COR", "AEP", 100);
        Flight closed = saveFlight("AG2022", "EZE", "COR", 100);
        closed.changeStatus(FlightStatus.CLOSED, Instant.now());
        flightRepository.saveAndFlush(closed);

        mockMvc.perform(get("/api/v1/flights?origin=COR").with(user()))
                .andExpect(jsonPath("$.totalElements").value(2));
        mockMvc.perform(get("/api/v1/flights?origin=COR&destination=AEP").with(user()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("AG2021"));
        mockMvc.perform(get("/api/v1/flights?status=CLOSED").with(user()))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].code").value("AG2022"));
        mockMvc.perform(get("/api/v1/flights?status=VOLANDO").with(user()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }

    // ---------- modificación y estados (RN-08) ----------

    @Test
    void elAdminModificaUnVuelo() throws Exception {
        Flight flight = saveFlight("AG2030", 100);

        mockMvc.perform(put("/api/v1/flights/" + flight.getId()).with(admin()).contentType(APPLICATION_JSON)
                        .content(updateBody("COR", "AEP", 150)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destination").value("AEP"))
                .andExpect(jsonPath("$.capacity").value(150));

        assertThat(jdbc.queryForObject("select capacity from flights where id = ?", Integer.class, flight.getId()))
                .isEqualTo(150);
    }

    @Test
    void permiteUnCambioDeEstadoValido() throws Exception {
        Flight flight = saveFlight("AG2031", 100);

        mockMvc.perform(patch("/api/v1/flights/" + flight.getId() + "/status").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        assertThat(flightStatus(flight.getId())).isEqualTo("CLOSED");
    }

    @Test
    void rechazaUnCambioDeEstadoInvalidoYNoModificaLosDatos() throws Exception {
        Flight flight = saveFlight("AG2032", 100);

        mockMvc.perform(patch("/api/v1/flights/" + flight.getId() + "/status").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));

        assertThat(flightStatus(flight.getId())).isEqualTo("SCHEDULED");
    }

    @Test
    void rechazaUnEstadoDesconocido() throws Exception {
        Flight flight = saveFlight("AG2033", 100);

        mockMvc.perform(patch("/api/v1/flights/" + flight.getId() + "/status").with(admin())
                        .contentType(APPLICATION_JSON).content("{\"status\":\"VOLANDO\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void unVueloCanceladoNoSePuedeModificar() throws Exception {
        Flight flight = saveFlight("AG2034", 100);
        flight.changeStatus(FlightStatus.CANCELLED, Instant.now());
        flightRepository.saveAndFlush(flight);

        mockMvc.perform(put("/api/v1/flights/" + flight.getId()).with(admin()).contentType(APPLICATION_JSON)
                        .content(updateBody("COR", "AEP", 150)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_MODIFIABLE"));

        assertThat(jdbc.queryForObject("select capacity from flights where id = ?", Integer.class, flight.getId()))
                .isEqualTo(100);
    }
}
