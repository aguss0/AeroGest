package ar.edu.utn.frc.aerogest.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.utn.frc.aerogest.reservation.client.AllocationOutcome;
import ar.edu.utn.frc.aerogest.reservation.client.FlightGateway;
import ar.edu.utn.frc.aerogest.reservation.domain.DocumentType;
import ar.edu.utn.frc.aerogest.reservation.domain.Passenger;
import ar.edu.utn.frc.aerogest.reservation.domain.Reservation;
import ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus;
import ar.edu.utn.frc.aerogest.reservation.dto.CreateReservationRequest;
import ar.edu.utn.frc.aerogest.reservation.dto.ReservationResponse;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import ar.edu.utn.frc.aerogest.reservation.repository.PassengerRepository;
import ar.edu.utn.frc.aerogest.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Orquestación de la reserva con Flight Service simulado. Cubre el flujo feliz, los rechazos
 * definitivos de Flight (RN-04, RN-05), los fallos de integración (RN-10), los duplicados (RN-06),
 * el acceso (RN-09), la cancelación (RN-07) y la reconciliación de reservas PENDING.
 */
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final long FLIGHT = 42L;
    private static final long PASSENGER = 7L;
    private static final CurrentUser OWNER = CurrentUser.user("user-1");
    private static final CurrentUser OTHER = CurrentUser.user("user-2");
    private static final CurrentUser ADMIN = CurrentUser.admin("admin-1");

    private final ReservationRepository reservations = mock(ReservationRepository.class);
    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private final FlightGateway flight = mock(FlightGateway.class);
    /** "Base" en memoria: lo que se guardó, por id. Permite verificar el estado final de cada reserva. */
    private final Map<UUID, Reservation> stored = new HashMap<>();
    private ReservationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new ReservationService(reservations, new PassengerService(passengers, clock), flight,
                clock, mock(PlatformTransactionManager.class));

        when(reservations.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> {
            Reservation reservation = inv.getArgument(0);
            stored.put(reservation.getId(), reservation);
            return reservation;
        });
        when(reservations.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(stored.get(inv.<UUID>getArgument(0))));

        Passenger passenger = Passenger.register("user-1", "Ana", "Pérez", DocumentType.DNI, "30123456", NOW);
        ReflectionTestUtils.setField(passenger, "id", PASSENGER);
        when(passengers.findById(PASSENGER)).thenReturn(Optional.of(passenger));
    }

    private CreateReservationRequest request() {
        return new CreateReservationRequest(PASSENGER, FLIGHT);
    }

    private void flightAllocates() {
        when(flight.allocateSeat(eq(FLIGHT), any(UUID.class)))
                .thenAnswer(inv -> new AllocationOutcome(inv.getArgument(1), FLIGHT, true, 99));
    }

    private void flightFailsWith(RuntimeException error) {
        when(flight.allocateSeat(eq(FLIGHT), any(UUID.class))).thenThrow(error);
    }

    private Reservation onlyStored() {
        assertThat(stored).hasSize(1);
        return stored.values().iterator().next();
    }

    private Reservation storedReservation(ReservationStatus status, String owner) {
        Reservation reservation = Reservation.create(PASSENGER, FLIGHT, owner, NOW.minus(Duration.ofMinutes(10)));
        if (status == ReservationStatus.CONFIRMED || status == ReservationStatus.CANCELLED) {
            reservation.confirm(NOW);
        }
        if (status == ReservationStatus.CANCELLED) {
            reservation.cancel(NOW);
        }
        stored.put(reservation.getId(), reservation);
        return reservation;
    }

    private void assertRule(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------- creación ----------

    @Test
    void confirmaLaReservaCuandoFlightAsignaLaPlaza() {
        flightAllocates();

        ReservationResponse created = service.create(OWNER, request());

        assertThat(created.status()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(created.createdByUserId()).isEqualTo("user-1");
        // Flight recibe el mismo id que se guardó: es la clave de idempotencia (RN-15)
        verify(flight).allocateSeat(FLIGHT, created.id());
        assertThat(onlyStored().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void sinPlazasLaReservaQuedaRechazadaYSePropagaElMotivo_RN05() {
        flightFailsWith(new BusinessRuleException(ErrorCode.NO_AVAILABILITY, "Sin plazas"));

        assertRule(() -> service.create(OWNER, request()), ErrorCode.NO_AVAILABILITY);

        assertThat(onlyStored().getStatus()).isEqualTo(ReservationStatus.REJECTED);
        verify(flight, never()).releaseSeat(anyLong(), any());   // no hubo plaza: no hay nada que liberar
    }

    @Test
    void vueloNoReservable_RN04() {
        flightFailsWith(new BusinessRuleException(ErrorCode.FLIGHT_NOT_RESERVABLE, "Cancelado"));

        assertRule(() -> service.create(OWNER, request()), ErrorCode.FLIGHT_NOT_RESERVABLE);
        assertThat(onlyStored().getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }

    @Test
    void vueloInexistente() {
        flightFailsWith(new BusinessRuleException(ErrorCode.FLIGHT_NOT_FOUND, "No existe"));

        assertRule(() -> service.create(OWNER, request()), ErrorCode.FLIGHT_NOT_FOUND);
        assertThat(onlyStored().getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }

    @Test
    void anteUnFalloDeIntegracionNoConfirmaLiberaLaPlazaYRechaza_RN10() {
        flightFailsWith(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_TIMEOUT, "Timeout"));

        assertRule(() -> service.create(OWNER, request()), ErrorCode.FLIGHT_SERVICE_TIMEOUT);

        Reservation reservation = onlyStored();
        // El timeout pudo haber ocupado la plaza: se libera (idempotente) antes de rechazar
        verify(flight).releaseSeat(FLIGHT, reservation.getId());
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }

    @Test
    void siTampocoSePuedeLiberarLaReservaQuedaPendingParaLaReconciliacion_RN10() {
        flightFailsWith(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Caído"));
        doThrow(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Caído"))
                .when(flight).releaseSeat(anyLong(), any());

        assertRule(() -> service.create(OWNER, request()), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);

        // Nunca CONFIRMED: queda PENDING y el job la resuelve cuando Flight vuelva
        assertThat(onlyStored().getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void unaRespuestaInvalidaDeFlightTampocoConfirma_RN10() {
        flightFailsWith(new FlightServiceException(ErrorCode.INVALID_FLIGHT_RESPONSE, "Ilegible"));

        assertRule(() -> service.create(OWNER, request()), ErrorCode.INVALID_FLIGHT_RESPONSE);
        assertThat(onlyStored().getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }

    @Test
    void noPermiteUnaSegundaReservaActivaParaElMismoVuelo_RN06() {
        when(reservations.existsByPassengerIdAndFlightIdAndStatusIn(eq(PASSENGER), eq(FLIGHT), anyCollection()))
                .thenReturn(true);

        assertRule(() -> service.create(OWNER, request()), ErrorCode.DUPLICATE_ACTIVE_RESERVATION);

        assertThat(stored).isEmpty();
        verify(flight, never()).allocateSeat(anyLong(), any());
    }

    @Test
    void elIndiceUnicoDeLaBaseFrenaDuplicadosSimultaneos_RN06() {
        when(reservations.saveAndFlush(any(Reservation.class))).thenThrow(new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_reservations_active_passenger_flight\""));

        assertRule(() -> service.create(OWNER, request()), ErrorCode.DUPLICATE_ACTIVE_RESERVATION);
        verify(flight, never()).allocateSeat(anyLong(), any());
    }

    @Test
    void noSePuedeReservarConUnPasajeroAjeno_RN09() {
        assertRule(() -> service.create(OTHER, request()), ErrorCode.ACCESS_DENIED);
        verify(flight, never()).allocateSeat(anyLong(), any());
    }

    @Test
    void pasajeroInexistente() {
        assertRule(() -> service.create(OWNER, new CreateReservationRequest(999L, FLIGHT)),
                ErrorCode.PASSENGER_NOT_FOUND);
    }

    // ---------- cancelación ----------

    @Test
    void cancelarLiberaLaPlazaYMarcaCancelled_RN07() {
        Reservation reservation = storedReservation(ReservationStatus.CONFIRMED, "user-1");

        ReservationResponse cancelled = service.cancel(OWNER, reservation.getId());

        assertThat(cancelled.status()).isEqualTo(ReservationStatus.CANCELLED);
        verify(flight).releaseSeat(FLIGHT, reservation.getId());
    }

    @Test
    void noSePuedeCancelarDosVecesNiSeLlamaAFlight_RN07() {
        Reservation reservation = storedReservation(ReservationStatus.CANCELLED, "user-1");

        assertRule(() -> service.cancel(OWNER, reservation.getId()), ErrorCode.INVALID_RESERVATION_STATE);
        verify(flight, never()).releaseSeat(anyLong(), any());
    }

    @Test
    void noSePuedeCancelarUnaReservaRechazada_RN08() {
        Reservation reservation = storedReservation(ReservationStatus.REJECTED, "user-1");
        reservation.reject(NOW);

        assertRule(() -> service.cancel(OWNER, reservation.getId()), ErrorCode.INVALID_RESERVATION_STATE);
    }

    @Test
    void siFlightNoRespondeLaReservaSigueConfirmadaYSePuedeReintentar() {
        Reservation reservation = storedReservation(ReservationStatus.CONFIRMED, "user-1");
        doThrow(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Caído"))
                .when(flight).releaseSeat(anyLong(), any());

        assertRule(() -> service.cancel(OWNER, reservation.getId()), ErrorCode.FLIGHT_SERVICE_UNAVAILABLE);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void unUsuarioNoPuedeCancelarNiVerUnaReservaAjena_RN09() {
        Reservation reservation = storedReservation(ReservationStatus.CONFIRMED, "user-1");

        assertRule(() -> service.cancel(OTHER, reservation.getId()), ErrorCode.ACCESS_DENIED);
        assertRule(() -> service.getById(OTHER, reservation.getId()), ErrorCode.ACCESS_DENIED);
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
    }

    @Test
    void unAdminPuedeCancelarCualquierReserva() {
        Reservation reservation = storedReservation(ReservationStatus.CONFIRMED, "user-1");

        assertThat(service.cancel(ADMIN, reservation.getId()).status()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void reservaInexistente() {
        assertRule(() -> service.getById(ADMIN, UUID.randomUUID()), ErrorCode.RESERVATION_NOT_FOUND);
    }

    // ---------- reconciliación ----------

    @Test
    void laReconciliacionLiberaYRechazaLasReservasPendingViejas() {
        Reservation first = storedReservation(ReservationStatus.PENDING, "user-1");
        Reservation second = storedReservation(ReservationStatus.PENDING, "user-2");
        when(reservations.findByStatusAndCreatedAtBefore(eq(ReservationStatus.PENDING),
                eq(NOW.minus(Duration.ofMinutes(5))), any(Pageable.class)))
                .thenReturn(List.of(first, second));

        int resolved = service.resolveStalePending(Duration.ofMinutes(5), 50);

        assertThat(resolved).isEqualTo(2);
        verify(flight).releaseSeat(FLIGHT, first.getId());
        verify(flight).releaseSeat(FLIGHT, second.getId());
        assertThat(first.getStatus()).isEqualTo(ReservationStatus.REJECTED);
        assertThat(second.getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }

    @Test
    void siFlightSigueCaidoLasReservasQuedanPendingParaLaProximaEjecucion() {
        Reservation pending = storedReservation(ReservationStatus.PENDING, "user-1");
        when(reservations.findByStatusAndCreatedAtBefore(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(pending));
        doThrow(new FlightServiceException(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE, "Caído"))
                .when(flight).releaseSeat(anyLong(), any());

        assertThat(service.resolveStalePending(Duration.ofMinutes(5), 50)).isZero();
        assertThat(pending.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void unaReservaQueFallaNoFrenaAlResto() {
        Reservation broken = storedReservation(ReservationStatus.PENDING, "user-1");
        Reservation ok = storedReservation(ReservationStatus.PENDING, "user-2");
        when(reservations.findByStatusAndCreatedAtBefore(any(), any(), any(Pageable.class)))
                .thenReturn(List.of(broken, ok));
        doThrow(new IllegalStateException("inesperado"))
                .when(flight).releaseSeat(FLIGHT, broken.getId());

        assertThat(service.resolveStalePending(Duration.ofMinutes(5), 50)).isEqualTo(1);
        assertThat(broken.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(ok.getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }
}
