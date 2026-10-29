package ar.edu.utn.frc.aerogest.reservation;

import static ar.edu.utn.frc.aerogest.reservation.TestAuth.USER_1;
import static ar.edu.utn.frc.aerogest.reservation.TestAuth.user;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ar.edu.utn.frc.aerogest.reservation.client.AllocationOutcome;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Concurrencia del lado de Reservation Service, contra PostgreSQL real.
 *
 * La no sobreventa de la última plaza (RN-05) la garantiza Flight Service y se prueba allí
 * (SeatAllocationIntegrationTest) y de punta a punta en scripts/smoke-reservations.ps1.
 * Acá se prueban las garantías propias de este servicio: que el índice único parcial frena
 * reservas duplicadas simultáneas (RN-06) y que @Version evita cancelar dos veces (RN-07).
 */
class ReservationConcurrencyIntegrationTest extends AbstractIntegrationTest {

    private static final long FLIGHT = 42L;
    private static final int REQUESTS = 10;

    private long registerPassenger() throws Exception {
        String json = mockMvc.perform(post("/api/v1/passengers").with(user(USER_1)).contentType(APPLICATION_JSON)
                        .content("""
                                {"firstName":"Ana","lastName":"Pérez","documentType":"DNI","documentNumber":"30123456"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(json, "$.id")).longValue();
    }

    private int reserve(long passengerId) throws Exception {
        return mockMvc.perform(post("/api/v1/reservations").with(user(USER_1)).contentType(APPLICATION_JSON)
                        .content("{\"passengerId\":%d,\"flightId\":%d}".formatted(passengerId, FLIGHT)))
                .andReturn().getResponse().getStatus();
    }

    private void flightAllocates() {
        when(flightGateway.allocateSeat(eq(FLIGHT), any(UUID.class)))
                .thenAnswer(inv -> new AllocationOutcome(inv.getArgument(1), FLIGHT, true, 99));
    }

    @Test
    void diezReservasSimultaneasDelMismoPasajeroYVueloDejanUnaSolaActiva_RN06() throws Exception {
        long passenger = registerPassenger();
        flightAllocates();

        List<Integer> statuses = runConcurrently(REQUESTS, i -> reserve(passenger));

        // Las que llegan juntas pasan la verificación previa; el índice único parcial frena a todas menos una
        assertThat(count(statuses, 201)).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(REQUESTS - 1);
        assertThat(reservationCount()).isEqualTo(1);
        assertThat(reservationCount("CONFIRMED")).isEqualTo(1);
        // Solo la reserva que se guardó pidió plaza a Flight
        verify(flightGateway, times(1)).allocateSeat(anyLong(), any());
    }

    @Test
    void diezCancelacionesSimultaneasDeLaMismaReservaSoloCancelanUnaVez_RN07() throws Exception {
        long passenger = registerPassenger();
        flightAllocates();
        assertThat(reserve(passenger)).isEqualTo(201);
        UUID reservation = jdbc.queryForObject("select id from reservations", UUID.class);

        List<Integer> statuses = runConcurrently(REQUESTS, i -> mockMvc
                .perform(post("/api/v1/reservations/" + reservation + "/cancel").with(user(USER_1)))
                .andReturn().getResponse().getStatus());

        // Gana una; las demás chocan con @Version (CONCURRENT_MODIFICATION) o ya la ven CANCELLED
        assertThat(count(statuses, 200)).isEqualTo(1);
        assertThat(count(statuses, 409)).isEqualTo(REQUESTS - 1);
        assertThat(reservationStatus(reservation)).isEqualTo("CANCELLED");
        // Varias pudieron pedir liberar la plaza; Flight es idempotente y la devuelve una sola vez (RN-15)
    }
}
