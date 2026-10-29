package ar.edu.utn.frc.aerogest.flight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.utn.frc.aerogest.flight.domain.AllocationStatus;
import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import ar.edu.utn.frc.aerogest.flight.domain.SeatAllocation;
import ar.edu.utn.frc.aerogest.flight.dto.FlightAvailabilityResponse;
import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.flight.repository.FlightRepository;
import ar.edu.utn.frc.aerogest.flight.repository.SeatAllocationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Lógica de asignación y liberación de plazas con repositorios simulados.
 * La garantía real de no sobreventa (el UPDATE atómico) se prueba contra PostgreSQL en la Parte 4.
 */
class SeatInventoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant DEPARTURE = NOW.plus(Duration.ofDays(10));
    private static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(2));
    private static final UUID RESERVATION = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private final FlightRepository flights = mock(FlightRepository.class);
    private final SeatAllocationRepository allocations = mock(SeatAllocationRepository.class);
    private SeatInventoryService service;

    @BeforeEach
    void setUp() {
        service = new SeatInventoryService(flights, allocations, Clock.fixed(NOW, ZoneOffset.UTC),
                mock(PlatformTransactionManager.class));
        when(allocations.saveAndFlush(any(SeatAllocation.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private Flight flight() {
        Flight flight = Flight.create("AG1001", "COR", "EZE", DEPARTURE, ARRIVAL, 100, NOW);
        ReflectionTestUtils.setField(flight, "id", 1L);
        return flight;
    }

    private void assertRule(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------- asignación ----------

    @Test
    void ocupaUnaPlazaYRegistraLaAsignacion() {
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty());
        when(flights.allocateSeat(eq(1L), eq(FlightStatus.SCHEDULED), any(Instant.class))).thenReturn(1);
        when(flights.findById(1L)).thenReturn(Optional.of(flight()));

        AllocationResult result = service.allocate(1L, RESERVATION);

        assertThat(result.created()).isTrue();
        assertThat(result.response().status()).isEqualTo(AllocationStatus.ALLOCATED);
        verify(allocations).saveAndFlush(any(SeatAllocation.class));
    }

    @Test
    void unReintentoDeLaMismaReservaNoOcupaOtraPlaza_RN15() {
        when(allocations.findById(RESERVATION))
                .thenReturn(Optional.of(SeatAllocation.allocate(RESERVATION, 1L, NOW)));
        when(flights.findById(1L)).thenReturn(Optional.of(flight()));

        AllocationResult result = service.allocate(1L, RESERVATION);

        assertThat(result.created()).isFalse();
        verify(flights, never()).allocateSeat(any(), any(), any());
    }

    @Test
    void rechazaAsignarUnaReservaCuyaPlazaYaFueLiberada() {
        SeatAllocation released = SeatAllocation.allocate(RESERVATION, 1L, NOW);
        released.release(NOW);
        when(allocations.findById(RESERVATION)).thenReturn(Optional.of(released));

        assertRule(() -> service.allocate(1L, RESERVATION), ErrorCode.ALLOCATION_CONFLICT);
        verify(flights, never()).allocateSeat(any(), any(), any());
    }

    @Test
    void rechazaAsignarUnaReservaAsociadaAOtroVuelo() {
        when(allocations.findById(RESERVATION))
                .thenReturn(Optional.of(SeatAllocation.allocate(RESERVATION, 2L, NOW)));

        assertRule(() -> service.allocate(1L, RESERVATION), ErrorCode.ALLOCATION_CONFLICT);
    }

    @Test
    void sinPlazasDisponiblesDevuelveNoAvailability_RN05() {
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty());
        when(flights.allocateSeat(eq(1L), eq(FlightStatus.SCHEDULED), any(Instant.class))).thenReturn(0);
        when(flights.findById(1L)).thenReturn(Optional.of(flight()));

        assertRule(() -> service.allocate(1L, RESERVATION), ErrorCode.NO_AVAILABILITY);
        verify(allocations, never()).saveAndFlush(any(SeatAllocation.class));
    }

    @Test
    void unVueloQueNoAdmiteReservasDevuelveNotReservable_RN04() {
        Flight closed = flight();
        closed.changeStatus(FlightStatus.CLOSED, NOW);
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty());
        when(flights.allocateSeat(eq(1L), eq(FlightStatus.SCHEDULED), any(Instant.class))).thenReturn(0);
        when(flights.findById(1L)).thenReturn(Optional.of(closed));

        assertRule(() -> service.allocate(1L, RESERVATION), ErrorCode.FLIGHT_NOT_RESERVABLE);
    }

    @Test
    void unVueloInexistenteDevuelveNotFound() {
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty());
        when(flights.allocateSeat(eq(1L), eq(FlightStatus.SCHEDULED), any(Instant.class))).thenReturn(0);
        when(flights.findById(1L)).thenReturn(Optional.empty());

        assertRule(() -> service.allocate(1L, RESERVATION), ErrorCode.FLIGHT_NOT_FOUND);
    }

    @Test
    void dosSolicitudesSimultaneasConLaMismaReservaTerminanEnUnaSolaAsignacion() {
        SeatAllocation firstRequestRecord = SeatAllocation.allocate(RESERVATION, 1L, NOW);
        // 1er intento: no hay registro y al insertar choca con el de la otra solicitud. 2do intento: lo encuentra.
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty(), Optional.of(firstRequestRecord));
        when(flights.allocateSeat(eq(1L), eq(FlightStatus.SCHEDULED), any(Instant.class))).thenReturn(1);
        when(allocations.saveAndFlush(any(SeatAllocation.class)))
                .thenThrow(new DataIntegrityViolationException("clave duplicada"));
        when(flights.findById(1L)).thenReturn(Optional.of(flight()));

        AllocationResult result = service.allocate(1L, RESERVATION);

        assertThat(result.created()).isFalse();
        verify(flights, times(1)).allocateSeat(any(), any(), any());
    }

    // ---------- liberación ----------

    @Test
    void liberaLaPlazaDeUnaReservaAsignada_RN07() {
        when(allocations.findById(RESERVATION))
                .thenReturn(Optional.of(SeatAllocation.allocate(RESERVATION, 1L, NOW)));
        when(allocations.markReleased(eq(RESERVATION), eq(AllocationStatus.ALLOCATED),
                eq(AllocationStatus.RELEASED), any(Instant.class))).thenReturn(1);
        when(flights.releaseSeat(eq(1L), any(Instant.class))).thenReturn(1);

        service.release(1L, RESERVATION);

        verify(flights, times(1)).releaseSeat(eq(1L), any(Instant.class));
    }

    @Test
    void siOtraLiberacionSimultaneaYaLaMarcoNoSeDevuelveLaPlazaOtraVez_RN07() {
        when(allocations.findById(RESERVATION))
                .thenReturn(Optional.of(SeatAllocation.allocate(RESERVATION, 1L, NOW)));
        when(allocations.markReleased(eq(RESERVATION), eq(AllocationStatus.ALLOCATED),
                eq(AllocationStatus.RELEASED), any(Instant.class))).thenReturn(0);

        service.release(1L, RESERVATION);

        verify(flights, never()).releaseSeat(any(), any());
    }

    @Test
    void liberarDosVecesNoDevuelveLaPlazaDosVeces_RN07() {
        SeatAllocation allocation = SeatAllocation.allocate(RESERVATION, 1L, NOW);
        allocation.release(NOW);
        when(allocations.findById(RESERVATION)).thenReturn(Optional.of(allocation));

        service.release(1L, RESERVATION);

        verify(allocations, never()).markReleased(any(), any(), any(), any());
        verify(flights, never()).releaseSeat(any(), any());
    }

    @Test
    void liberarUnaReservaQueNuncaOcupoPlazaDejaUnaFichaYNoToscaElContador() {
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty());
        when(flights.existsById(1L)).thenReturn(true);

        service.release(1L, RESERVATION);

        ArgumentCaptor<SeatAllocation> saved = ArgumentCaptor.forClass(SeatAllocation.class);
        verify(allocations).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(AllocationStatus.RELEASED);
        verify(flights, never()).releaseSeat(any(), any());
    }

    @Test
    void liberarEnUnVueloInexistenteDevuelveNotFound() {
        when(allocations.findById(RESERVATION)).thenReturn(Optional.empty());
        when(flights.existsById(1L)).thenReturn(false);

        assertRule(() -> service.release(1L, RESERVATION), ErrorCode.FLIGHT_NOT_FOUND);
    }

    // ---------- disponibilidad ----------

    @Test
    void laDisponibilidadIndicaQueUnVueloProgramadoAdmiteReservas() {
        when(flights.findById(1L)).thenReturn(Optional.of(flight()));

        FlightAvailabilityResponse response = service.getAvailability(1L);

        assertThat(response.acceptsReservations()).isTrue();
        assertThat(response.availableSeats()).isEqualTo(100);
    }

    @Test
    void laDisponibilidadIndicaQueUnVueloCerradoNoAdmiteReservas() {
        Flight closed = flight();
        closed.changeStatus(FlightStatus.CLOSED, NOW);
        when(flights.findById(1L)).thenReturn(Optional.of(closed));

        assertThat(service.getAvailability(1L).acceptsReservations()).isFalse();
    }
}
