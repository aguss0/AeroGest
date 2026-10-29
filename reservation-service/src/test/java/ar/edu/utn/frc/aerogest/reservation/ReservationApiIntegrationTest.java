package ar.edu.utn.frc.aerogest.reservation;

import static ar.edu.utn.frc.aerogest.reservation.TestAuth.USER_1;
import static ar.edu.utn.frc.aerogest.reservation.TestAuth.USER_2;
import static ar.edu.utn.frc.aerogest.reservation.TestAuth.admin;
import static ar.edu.utn.frc.aerogest.reservation.TestAuth.service;
import static ar.edu.utn.frc.aerogest.reservation.TestAuth.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.utn.frc.aerogest.reservation.client.AllocationOutcome;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * API de pasajeros y reservas de punta a punta (seguridad + validación + reglas + base real),
 * con Flight Service simulado. Cada prueba comprueba la respuesta HTTP y su efecto en la base.
 */
class ReservationApiIntegrationTest extends AbstractIntegrationTest {

    private static final long FLIGHT = 42L;

    // ---------- helpers ----------

    private long registerPassenger(RequestPostProcessor auth, String document) throws Exception {
        String body = """
                {"firstName":"Ana","lastName":"Pérez","documentType":"DNI","documentNumber":"%s"}
                """.formatted(document);
        String json = mockMvc.perform(post("/api/v1/passengers").with(auth).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private String reservationBody(long passengerId, long flightId) {
        return """
                {"passengerId":%d,"flightId":%d}
                """.formatted(passengerId, flightId);
    }

    private void flightAllocates() {
        when(flightGateway.allocateSeat(eq(FLIGHT), any(UUID.class)))
                .thenAnswer(inv -> new AllocationOutcome(inv.getArgument(1), FLIGHT, true, 99));
    }

    private UUID createConfirmed(RequestPostProcessor auth, long passengerId) throws Exception {
        flightAllocates();
        String json = mockMvc.perform(post("/api/v1/reservations").with(auth)
                        .contentType(APPLICATION_JSON).content(reservationBody(passengerId, FLIGHT)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(json, "$.id"));
    }

    // ---------- seguridad (RN-09) ----------

    @Test
    void sinCredencialesDevuelve401() throws Exception {
        mockMvc.perform(get("/api/v1/reservations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(post("/api/v1/reservations").contentType(APPLICATION_JSON).content(reservationBody(1, FLIGHT)))
                .andExpect(status().isUnauthorized());

        assertThat(reservationCount()).isZero();
    }

    @Test
    void elRolServiceNoTieneAccesoALaApiPublica_403() throws Exception {
        mockMvc.perform(get("/api/v1/reservations").with(service()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
    }

    @Test
    void unUsuarioNoPuedeVerNiCancelarUnaReservaAjena_403() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        UUID reservation = createConfirmed(user(USER_1), passenger);

        mockMvc.perform(get("/api/v1/reservations/" + reservation).with(user(USER_2)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(post("/api/v1/reservations/" + reservation + "/cancel").with(user(USER_2)))
                .andExpect(status().isForbidden());

        assertThat(reservationStatus(reservation)).isEqualTo("CONFIRMED");
        verify(flightGateway, never()).releaseSeat(anyLong(), any());
    }

    @Test
    void unUsuarioNoPuedeReservarConUnPasajeroAjeno_403() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_2))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isForbidden());

        assertThat(reservationCount()).isZero();
        verify(flightGateway, never()).allocateSeat(anyLong(), any());
    }

    @Test
    void cadaUsuarioListaSoloSusReservasYUnAdminTodas() throws Exception {
        UUID mine = createConfirmed(user(USER_1), registerPassenger(user(USER_1), "30123456"));
        createConfirmed(user(USER_2), registerPassenger(user(USER_2), "30999888"));

        mockMvc.perform(get("/api/v1/reservations").with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(mine.toString()));
        mockMvc.perform(get("/api/v1/reservations").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // ---------- pasajeros ----------

    @Test
    void registraUnPasajeroYRechazaElDocumentoDuplicado_RN14() throws Exception {
        registerPassenger(user(USER_1), "30123456");

        // Mismo DNI con otro formato y otro usuario: sigue siendo la misma persona
        mockMvc.perform(post("/api/v1/passengers").with(user(USER_2)).contentType(APPLICATION_JSON)
                        .content("""
                                {"firstName":"Otra","lastName":"Persona","documentType":"DNI","documentNumber":"30.123.456"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_PASSENGER_DOCUMENT"));

        assertThat(passengerCount()).isEqualTo(1);
    }

    @Test
    void validaLosDatosDelPasajero_400() throws Exception {
        mockMvc.perform(post("/api/v1/passengers").with(user(USER_1)).contentType(APPLICATION_JSON)
                        .content("""
                                {"firstName":"","lastName":"Pérez","documentNumber":"30123456"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors.length()").value(2));   // firstName y documentType

        mockMvc.perform(post("/api/v1/passengers").with(user(USER_1)).contentType(APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ana","lastName":"Pérez","documentType":"DNI","documentNumber":"ABC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PASSENGER_DATA"));

        assertThat(passengerCount()).isZero();
    }

    @Test
    void elDuenoCorrigeElNombreDeSuPasajero() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");

        mockMvc.perform(put("/api/v1/passengers/" + passenger).with(user(USER_1)).contentType(APPLICATION_JSON)
                        .content("""
                                {"firstName":"María","lastName":"Gómez"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName").value("María"))
                .andExpect(jsonPath("$.documentNumber").value("30123456"));

        assertThat(jdbc.queryForObject("select first_name from passengers where id = ?", String.class, passenger))
                .isEqualTo("María");
    }

    // ---------- creación de reservas ----------

    @Test
    void creaUnaReservaConfirmada() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        flightAllocates();

        String json = mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .header("X-Correlation-Id", "demo-123")
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", "demo-123"))
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.passengerId").value(passenger))
                .andExpect(jsonPath("$.flightId").value(FLIGHT))
                .andReturn().getResponse().getContentAsString();

        UUID id = UUID.fromString(JsonPath.read(json, "$.id"));
        assertThat(reservationStatus(id)).isEqualTo("CONFIRMED");
        verify(flightGateway).allocateSeat(FLIGHT, id);
    }

    @Test
    void capacidadAgotada_409YReservaRechazada_RN05() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        when(flightGateway.allocateSeat(anyLong(), any()))
                .thenThrow(new BusinessRuleException(ErrorCode.NO_AVAILABILITY, "El vuelo no tiene plazas disponibles"));

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_AVAILABILITY"));

        assertThat(reservationCount("REJECTED")).isEqualTo(1);
        assertThat(reservationCount("CONFIRMED")).isZero();
    }

    @Test
    void vueloNoReservable_409_RN04() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        when(flightGateway.allocateSeat(anyLong(), any()))
                .thenThrow(new BusinessRuleException(ErrorCode.FLIGHT_NOT_RESERVABLE, "Cancelado"));

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_RESERVABLE"));

        assertThat(reservationCount("REJECTED")).isEqualTo(1);
    }

    @Test
    void vueloInexistente_404() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        when(flightGateway.allocateSeat(anyLong(), any()))
                .thenThrow(new BusinessRuleException(ErrorCode.FLIGHT_NOT_FOUND, "No existe"));

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, 999)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FLIGHT_NOT_FOUND"));

        assertThat(reservationCount("CONFIRMED")).isZero();
    }

    @Test
    void reservaActivaDuplicada_409_RN06() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        createConfirmed(user(USER_1), passenger);

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_ACTIVE_RESERVATION"));

        assertThat(reservationCount()).isEqualTo(1);
    }

    @Test
    void trasCancelarSePuedeVolverAReservarElMismoVuelo_RN06() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        UUID first = createConfirmed(user(USER_1), passenger);
        mockMvc.perform(post("/api/v1/reservations/" + first + "/cancel").with(user(USER_1)))
                .andExpect(status().isOk());

        // El índice único es parcial: solo cuenta PENDING y CONFIRMED
        createConfirmed(user(USER_1), passenger);

        assertThat(reservationCount("CANCELLED")).isEqualTo(1);
        assertThat(reservationCount("CONFIRMED")).isEqualTo(1);
    }

    @Test
    void validaLaSolicitudDeReserva_400() throws Exception {
        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content("{\"passengerId\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        assertThat(reservationCount()).isZero();
    }

    // ---------- integración con Flight (RN-10) ----------

    @Test
    void flightFueraDeServicio_503YNuncaConfirmada_RN10() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        when(flightGateway.allocateSeat(anyLong(), any()))
                .thenThrow(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Flight Service no está disponible"));

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FLIGHT_SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.correlationId").exists());

        // Se intentó liberar (por si la plaza se llegó a ocupar) y la reserva quedó rechazada
        verify(flightGateway).releaseSeat(eq(FLIGHT), any(UUID.class));
        assertThat(reservationCount("REJECTED")).isEqualTo(1);
        assertThat(reservationCount("CONFIRMED")).isZero();
    }

    @Test
    void timeoutDeFlight_504_RN10() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        when(flightGateway.allocateSeat(anyLong(), any()))
                .thenThrow(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_TIMEOUT, "Flight Service no respondió a tiempo"));

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("FLIGHT_SERVICE_TIMEOUT"));

        assertThat(reservationCount("CONFIRMED")).isZero();
    }

    @Test
    void respuestaInvalidaDeFlight_502_RN10() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        when(flightGateway.allocateSeat(anyLong(), any()))
                .thenThrow(new FlightServiceException(ErrorCode.INVALID_FLIGHT_RESPONSE, "Respuesta ilegible"));

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("INVALID_FLIGHT_RESPONSE"));

        assertThat(reservationCount("CONFIRMED")).isZero();
    }

    @Test
    void siTampocoSePuedeLiberarLaReservaQuedaPending_RN10() throws Exception {
        long passenger = registerPassenger(user(USER_1), "30123456");
        FlightServiceException down = new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Caído");
        when(flightGateway.allocateSeat(anyLong(), any())).thenThrow(down);
        doThrow(down).when(flightGateway).releaseSeat(anyLong(), any());

        mockMvc.perform(post("/api/v1/reservations").with(user(USER_1))
                        .contentType(APPLICATION_JSON).content(reservationBody(passenger, FLIGHT)))
                .andExpect(status().isServiceUnavailable());

        assertThat(reservationCount("PENDING")).isEqualTo(1);
        assertThat(reservationCount("CONFIRMED")).isZero();
    }

    // ---------- cancelación (RN-07) ----------

    @Test
    void cancelacionCorrectaLiberaLaPlaza() throws Exception {
        UUID reservation = createConfirmed(user(USER_1), registerPassenger(user(USER_1), "30123456"));

        mockMvc.perform(post("/api/v1/reservations/" + reservation + "/cancel").with(user(USER_1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        verify(flightGateway).releaseSeat(FLIGHT, reservation);
        assertThat(reservationStatus(reservation)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelacionRepetida_409() throws Exception {
        UUID reservation = createConfirmed(user(USER_1), registerPassenger(user(USER_1), "30123456"));
        mockMvc.perform(post("/api/v1/reservations/" + reservation + "/cancel").with(user(USER_1)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/reservations/" + reservation + "/cancel").with(user(USER_1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_RESERVATION_STATE"));

        verify(flightGateway).releaseSeat(FLIGHT, reservation);   // una sola vez
        assertThat(reservationStatus(reservation)).isEqualTo("CANCELLED");
    }

    @Test
    void cancelarConFlightCaido_503YLaReservaSigueConfirmada() throws Exception {
        UUID reservation = createConfirmed(user(USER_1), registerPassenger(user(USER_1), "30123456"));
        doThrow(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Caído"))
                .when(flightGateway).releaseSeat(anyLong(), any());

        mockMvc.perform(post("/api/v1/reservations/" + reservation + "/cancel").with(user(USER_1)))
                .andExpect(status().isServiceUnavailable());

        assertThat(reservationStatus(reservation)).isEqualTo("CONFIRMED");
    }

    @Test
    void unAdminPuedeCancelarUnaReservaAjena() throws Exception {
        UUID reservation = createConfirmed(user(USER_1), registerPassenger(user(USER_1), "30123456"));

        mockMvc.perform(post("/api/v1/reservations/" + reservation + "/cancel").with(admin()))
                .andExpect(status().isOk());

        assertThat(reservationStatus(reservation)).isEqualTo("CANCELLED");
    }

    @Test
    void reservaInexistente_404() throws Exception {
        mockMvc.perform(get("/api/v1/reservations/" + UUID.randomUUID()).with(user(USER_1)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESERVATION_NOT_FOUND"));
        mockMvc.perform(get("/api/v1/reservations/no-es-un-uuid").with(user(USER_1)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
    }
}
