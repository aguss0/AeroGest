package ar.edu.utn.frc.aerogest.flight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import ar.edu.utn.frc.aerogest.flight.dto.ChangeStatusRequest;
import ar.edu.utn.frc.aerogest.flight.dto.CreateFlightRequest;
import ar.edu.utn.frc.aerogest.flight.dto.FlightResponse;
import ar.edu.utn.frc.aerogest.flight.dto.FlightSearchCriteria;
import ar.edu.utn.frc.aerogest.flight.dto.PageResponse;
import ar.edu.utn.frc.aerogest.flight.dto.UpdateFlightRequest;
import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.flight.repository.FlightRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

/** Casos de uso de vuelos con el repositorio simulado (sin base de datos). */
class FlightServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant DEPARTURE = NOW.plus(Duration.ofDays(10));
    private static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(2));

    private final FlightRepository repository = mock(FlightRepository.class);
    private final FlightService service = new FlightService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    private CreateFlightRequest validCreate() {
        return new CreateFlightRequest("AG1001", "COR", "EZE", DEPARTURE, ARRIVAL, 100);
    }

    private Flight existingFlight() {
        return Flight.create("AG1001", "COR", "EZE", DEPARTURE, ARRIVAL, 100, NOW);
    }

    private void assertRule(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void creaUnVueloValido() {
        when(repository.existsByCode("AG1001")).thenReturn(false);
        when(repository.saveAndFlush(any(Flight.class))).thenAnswer(inv -> inv.getArgument(0));

        FlightResponse response = service.create(validCreate());

        assertThat(response.code()).isEqualTo("AG1001");
        assertThat(response.status()).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(response.capacity()).isEqualTo(100);
        assertThat(response.availableSeats()).isEqualTo(100);
    }

    @Test
    void rechazaCodigoDuplicadoSinGuardar_RN01() {
        when(repository.existsByCode("AG1001")).thenReturn(true);

        assertRule(() -> service.create(validCreate()), ErrorCode.DUPLICATE_FLIGHT_CODE);
        verify(repository, never()).saveAndFlush(any(Flight.class));
    }

    @Test
    void rechazaCodigoDuplicadoDetectadoPorLaBase_altasSimultaneas_RN01() {
        when(repository.existsByCode("AG1001")).thenReturn(false);
        when(repository.saveAndFlush(any(Flight.class))).thenThrow(new DataIntegrityViolationException(
                "error", new RuntimeException("duplicate key value violates unique constraint \"uq_flights_code\"")));

        assertRule(() -> service.create(validCreate()), ErrorCode.DUPLICATE_FLIGHT_CODE);
    }

    @Test
    void noOcultaOtrosErroresDeIntegridad() {
        when(repository.existsByCode("AG1001")).thenReturn(false);
        when(repository.saveAndFlush(any(Flight.class))).thenThrow(new DataIntegrityViolationException(
                "error", new RuntimeException("violates check constraint \"ck_flights_capacity\"")));

        assertThatThrownBy(() -> service.create(validCreate()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void consultaDeVueloInexistenteDevuelveNotFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertRule(() -> service.getById(99L), ErrorCode.FLIGHT_NOT_FOUND);
    }

    @Test
    void cambiaElEstadoConUnaTransicionValida() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingFlight()));

        FlightResponse response = service.changeStatus(1L, new ChangeStatusRequest(FlightStatus.CLOSED));

        assertThat(response.status()).isEqualTo(FlightStatus.CLOSED);
    }

    @Test
    void rechazaUnaTransicionInvalida_RN08() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingFlight()));

        assertRule(() -> service.changeStatus(1L, new ChangeStatusRequest(FlightStatus.COMPLETED)),
                ErrorCode.INVALID_STATUS_TRANSITION);
    }

    @Test
    void actualizaLosDatosDelVuelo() {
        when(repository.findById(1L)).thenReturn(Optional.of(existingFlight()));

        FlightResponse response = service.update(1L,
                new UpdateFlightRequest("COR", "AEP", DEPARTURE, ARRIVAL, 150));

        assertThat(response.destination()).isEqualTo("AEP");
        assertThat(response.capacity()).isEqualTo(150);
    }

    @Test
    void actualizarUnVueloInexistenteDevuelveNotFound() {
        when(repository.findById(5L)).thenReturn(Optional.empty());

        assertRule(() -> service.update(5L, new UpdateFlightRequest("COR", "AEP", DEPARTURE, ARRIVAL, 150)),
                ErrorCode.FLIGHT_NOT_FOUND);
    }

    @Test
    void laBusquedaLimitaElTamanoDePagina() {
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(existingFlight())));

        PageResponse<FlightResponse> response = service.search(
                new FlightSearchCriteria("COR", null, null, null, null), 0, 5000);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(FlightService.MAX_PAGE_SIZE);
        assertThat(response.content()).hasSize(1);
    }
}
