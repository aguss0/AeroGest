package ar.edu.utn.frc.aerogest.flight;

import static ar.edu.utn.frc.aerogest.flight.TestAuth.service;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Asignación y liberación de plazas contra PostgreSQL real: RN-04, RN-05, RN-07 y RN-15,
 * incluidas las pruebas con solicitudes concurrentes.
 */
class SeatAllocationIntegrationTest extends AbstractIntegrationTest {

    private int allocate(Long flightId, UUID reservationId) throws Exception {
        return mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flightId)
                        .with(service()).contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + reservationId + "\"}"))
                .andReturn().getResponse().getStatus();
    }

    private int release(Long flightId, UUID reservationId) throws Exception {
        return mockMvc.perform(delete("/internal/v1/flights/{id}/seat-allocations/{rid}", flightId, reservationId)
                        .with(service()))
                .andReturn().getResponse().getStatus();
    }

    // ---------- asignación ----------

    @Test
    void ocupaUnaPlazaYLaRegistra() throws Exception {
        Flight flight = saveFlight("SA3001", 10);
        UUID reservation = UUID.randomUUID();

        mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flight.getId())
                        .with(service()).contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + reservation + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ALLOCATED"))
                .andExpect(jsonPath("$.availableSeats").value(9));

        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
        assertThat(allocationRows(flight.getId(), "ALLOCATED")).isEqualTo(1);
    }

    @Test
    void repetirLaAsignacionNoOcupaOtraPlaza_RN15() throws Exception {
        Flight flight = saveFlight("SA3002", 10);
        UUID reservation = UUID.randomUUID();

        assertThat(allocate(flight.getId(), reservation)).isEqualTo(201);
        assertThat(allocate(flight.getId(), reservation)).isEqualTo(200);

        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
        assertThat(allocationRows(flight.getId(), "ALLOCATED")).isEqualTo(1);
    }

    @Test
    void laUltimaPlazaSoloSeAsignaUnaVez_RN05() throws Exception {
        Flight flight = saveFlight("SA3003", 1);

        assertThat(allocate(flight.getId(), UUID.randomUUID())).isEqualTo(201);
        UUID rejected = UUID.randomUUID();
        mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flight.getId())
                        .with(service()).contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + rejected + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_AVAILABILITY"));

        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from seat_allocations where reservation_id = ?",
                Integer.class, rejected)).isZero();
    }

    @Test
    void unVueloCerradoNoAdmiteAsignaciones_RN04() throws Exception {
        Flight flight = saveFlight("SA3004", 10);
        flight.changeStatus(FlightStatus.CLOSED, Instant.now());
        flightRepository.saveAndFlush(flight);

        mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flight.getId())
                        .with(service()).contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_RESERVABLE"));

        assertThat(seatsTaken(flight.getId())).isZero();
    }

    @Test
    void unVueloInexistenteDevuelve404() throws Exception {
        mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", 999999)
                        .with(service()).contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));
    }

    // ---------- liberación ----------

    @Test
    void liberarDevuelveLaPlazaUnaSolaVez_RN07() throws Exception {
        Flight flight = saveFlight("SA3010", 5);
        UUID reservation = UUID.randomUUID();
        allocate(flight.getId(), reservation);
        assertThat(seatsTaken(flight.getId())).isEqualTo(1);

        assertThat(release(flight.getId(), reservation)).isEqualTo(204);
        assertThat(seatsTaken(flight.getId())).isZero();
        assertThat(allocationRows(flight.getId(), "RELEASED")).isEqualTo(1);

        // Repetir la liberación no devuelve la plaza otra vez
        assertThat(release(flight.getId(), reservation)).isEqualTo(204);
        assertThat(seatsTaken(flight.getId())).isZero();
    }

    @Test
    void unaAsignacionTardiaDeUnaReservaYaLiberadaSeRechaza() throws Exception {
        Flight flight = saveFlight("SA3011", 5);
        UUID reservation = UUID.randomUUID();

        // La liberación llega primero (la asignación original se perdió por un timeout)
        assertThat(release(flight.getId(), reservation)).isEqualTo(204);
        assertThat(seatsTaken(flight.getId())).isZero();

        mockMvc.perform(post("/internal/v1/flights/{id}/seat-allocations", flight.getId())
                        .with(service()).contentType(APPLICATION_JSON)
                        .content("{\"reservationId\":\"" + reservation + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALLOCATION_CONFLICT"));

        assertThat(seatsTaken(flight.getId())).isZero();
    }

    @Test
    void despuesDeLiberarSePuedeOcuparLaPlazaConOtraReserva() throws Exception {
        Flight flight = saveFlight("SA3012", 1);
        UUID first = UUID.randomUUID();
        allocate(flight.getId(), first);
        assertThat(allocate(flight.getId(), UUID.randomUUID())).isEqualTo(409);

        release(flight.getId(), first);

        assertThat(allocate(flight.getId(), UUID.randomUUID())).isEqualTo(201);
        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
    }

    // ---------- concurrencia ----------

    @Test
    void veinteSolicitudesSimultaneasPorLaUltimaPlazaConfirmanSoloUna() throws Exception {
        Flight flight = saveFlight("SA3020", 1);

        List<Integer> statuses = runConcurrently(20, i -> allocate(flight.getId(), UUID.randomUUID()));

        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(19);
        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
        assertThat(allocationRows(flight.getId(), "ALLOCATED")).isEqualTo(1);
    }

    @Test
    void treintaSolicitudesSimultaneasConCapacidadCincoOcupanExactamenteCinco() throws Exception {
        Flight flight = saveFlight("SA3021", 5);

        List<Integer> statuses = runConcurrently(30, i -> allocate(flight.getId(), UUID.randomUUID()));

        assertThat(count(statuses, 201)).isEqualTo(5);
        assertThat(count(statuses, 409)).isEqualTo(25);
        assertThat(seatsTaken(flight.getId())).isEqualTo(5);
        assertThat(allocationRows(flight.getId(), "ALLOCATED")).isEqualTo(5);
    }

    @Test
    void diezSolicitudesSimultaneasDeLaMismaReservaOcupanUnaSolaPlaza_RN15() throws Exception {
        Flight flight = saveFlight("SA3022", 5);
        UUID reservation = UUID.randomUUID();

        List<Integer> statuses = runConcurrently(10, i -> allocate(flight.getId(), reservation));

        assertThat(statuses).allMatch(s -> s == 200 || s == 201);
        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
        assertThat(allocationRows(flight.getId(), "ALLOCATED")).isEqualTo(1);
    }

    @Test
    void diezLiberacionesSimultaneasDeLaMismaReservaDevuelvenUnaSolaPlaza_RN07() throws Exception {
        Flight flight = saveFlight("SA3023", 5);
        UUID reservation = UUID.randomUUID();
        allocate(flight.getId(), reservation);
        allocate(flight.getId(), UUID.randomUUID());
        assertThat(seatsTaken(flight.getId())).isEqualTo(2);

        List<Integer> statuses = runConcurrently(10, i -> release(flight.getId(), reservation));

        assertThat(statuses).allMatch(s -> s == 204);
        assertThat(seatsTaken(flight.getId())).isEqualTo(1);
        assertThat(allocationRows(flight.getId(), "RELEASED")).isEqualTo(1);
        assertThat(allocationRows(flight.getId(), "ALLOCATED")).isEqualTo(1);
    }
}
