package ar.edu.utn.frc.aerogest.reservation.service;

import ar.edu.utn.frc.aerogest.reservation.client.FlightGateway;
import ar.edu.utn.frc.aerogest.reservation.domain.Passenger;
import ar.edu.utn.frc.aerogest.reservation.domain.Reservation;
import ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus;
import ar.edu.utn.frc.aerogest.reservation.dto.CreateReservationRequest;
import ar.edu.utn.frc.aerogest.reservation.dto.PageResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.ReservationResponse;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import ar.edu.utn.frc.aerogest.reservation.repository.ReservationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proceso de reserva y cancelación, orquestado contra Flight Service.
 *
 * Creación (ADR 0001, decisión 1):
 *  1. Transacción corta: valida pasajero y RN-06, y guarda la reserva en PENDING.
 *  2. Sin transacción abierta: pide la plaza a Flight Service (idempotente por id de reserva, RN-15).
 *  3. Transacción corta: CONFIRMED si Flight asignó la plaza; REJECTED si la negó.
 *  Si el resultado es incierto (timeout, caída), nunca se confirma (RN-10): se libera la plaza y se
 *  rechaza. Si tampoco se puede liberar, la reserva queda PENDING y la resuelve el job de reconciliación.
 *
 * La llamada HTTP queda fuera de las transacciones para no retener una conexión ni bloqueos de la
 * base mientras se espera a otro servicio.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    static final int MAX_PAGE_SIZE = 100;
    private static final List<ReservationStatus> ACTIVE_STATUSES = Arrays.stream(ReservationStatus.values())
            .filter(ReservationStatus::isActive)
            .toList();

    private final ReservationRepository reservationRepository;
    private final PassengerService passengerService;
    private final FlightGateway flightGateway;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public ReservationService(ReservationRepository reservationRepository,
                              PassengerService passengerService,
                              FlightGateway flightGateway,
                              Clock clock,
                              PlatformTransactionManager transactionManager) {
        this.reservationRepository = reservationRepository;
        this.passengerService = passengerService;
        this.flightGateway = flightGateway;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    // ---------------------------------------------------------------- creación

    public ReservationResponse create(CurrentUser user, CreateReservationRequest request) {
        Reservation pending = transaction.execute(status -> registerPending(user, request));
        UUID id = pending.getId();
        long flightId = pending.getFlightId();

        try {
            flightGateway.allocateSeat(flightId, id);
        } catch (FlightServiceException e) {
            // Resultado incierto: la plaza pudo haber quedado ocupada. Se libera antes de rechazar.
            log.warn("Asignación incierta para la reserva {} (vuelo {}): {}", id, flightId, e.getMessage());
            runKeepingOriginal(e, () -> compensate(id, flightId));
            throw e;
        } catch (BusinessRuleException e) {
            // Flight negó la plaza de forma definitiva (sin plazas, no reservable, inexistente)
            log.info("Reserva {} rechazada por Flight Service: {}", id, e.getErrorCode());
            runKeepingOriginal(e, () -> resolve(id, r -> r.reject(now())));
            throw e;
        }
        return ReservationResponse.from(resolve(id, r -> r.confirm(now())));
    }

    private Reservation registerPending(CurrentUser user, CreateReservationRequest request) {
        Passenger passenger = passengerService.findAccessible(user, request.passengerId());
        // Verificación previa de RN-06 para dar un error claro...
        if (reservationRepository.existsByPassengerIdAndFlightIdAndStatusIn(
                passenger.getId(), request.flightId(), ACTIVE_STATUSES)) {
            throw duplicateActive();
        }
        Reservation reservation = Reservation.create(passenger.getId(), request.flightId(), user.userId(), now());
        try {
            return reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException e) {
            // ...pero la garantía real es el índice único parcial, que cubre solicitudes simultáneas
            if (PassengerService.violates(e, "uq_reservations_active_passenger_flight")) {
                throw duplicateActive();
            }
            throw e;
        }
    }

    // ------------------------------------------------------------ cancelación

    /**
     * RN-07: libera la plaza en Flight y recién entonces marca CANCELLED. Si Flight no responde,
     * la reserva sigue CONFIRMED y se puede reintentar (liberar es idempotente). Si dos
     * cancelaciones llegan a la vez, Flight devuelve la plaza una sola vez y la segunda
     * cancelación recibe un conflicto.
     */
    public ReservationResponse cancel(CurrentUser user, UUID id) {
        Reservation reservation = findAccessible(user, id);
        reservation.checkCancellable();
        flightGateway.releaseSeat(reservation.getFlightId(), id);
        return ReservationResponse.from(resolve(id, r -> r.cancel(now())));
    }

    // -------------------------------------------------------------- consultas

    @Transactional(readOnly = true)
    public ReservationResponse getById(CurrentUser user, UUID id) {
        return ReservationResponse.from(findAccessible(user, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<ReservationResponse> list(CurrentUser user, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
        Page<Reservation> result = user.admin()
                ? reservationRepository.findAll(pageable)
                : reservationRepository.findByCreatedByUserId(user.userId(), pageable);
        return PageResponse.from(result.map(ReservationResponse::from));
    }

    // --------------------------------------------------------- reconciliación

    /**
     * Resuelve reservas que quedaron PENDING por un fallo de integración: libera su plaza en
     * Flight (por si se llegó a ocupar) y las marca REJECTED. Solo toma reservas más viejas que
     * {@code olderThan}, que debe superar holgadamente la duración de una creación normal
     * (timeouts incluidos) para no competir con una solicitud todavía en curso.
     *
     * @return cuántas reservas se resolvieron
     */
    public int resolveStalePending(Duration olderThan, int batchSize) {
        Instant cutoff = now().minus(olderThan);
        List<Reservation> stale = reservationRepository.findByStatusAndCreatedAtBefore(
                ReservationStatus.PENDING, cutoff, PageRequest.of(0, batchSize, Sort.by("createdAt")));
        int resolved = 0;
        for (Reservation reservation : stale) {
            try {
                if (compensate(reservation.getId(), reservation.getFlightId())) {
                    resolved++;
                }
            } catch (RuntimeException e) {
                // Una reserva problemática no frena al resto; se reintenta en la próxima ejecución
                log.warn("No se pudo resolver la reserva PENDING {}: {}", reservation.getId(), e.getMessage());
            }
        }
        return resolved;
    }

    // --------------------------------------------------------------- soporte

    /**
     * Libera la plaza (idempotente) y rechaza la reserva.
     *
     * @return false si Flight no respondió: la reserva sigue PENDING y se reintenta más tarde
     */
    private boolean compensate(UUID id, long flightId) {
        try {
            flightGateway.releaseSeat(flightId, id);
        } catch (FlightServiceException e) {
            log.warn("La reserva {} queda PENDING: no se pudo liberar su plaza ({}). "
                    + "La resolverá el job de reconciliación", id, e.getMessage());
            return false;
        }
        resolve(id, r -> r.reject(now()));
        return true;
    }

    /** Aplica una transición en una transacción propia. @Version detecta modificaciones simultáneas. */
    private Reservation resolve(UUID id, Consumer<Reservation> transition) {
        return transaction.execute(status -> {
            Reservation reservation = findOrThrow(id);
            transition.accept(reservation);
            return reservationRepository.saveAndFlush(reservation);
        });
    }

    /** Ejecuta un paso secundario sin perder el error principal, que es el que ve el cliente. */
    private static void runKeepingOriginal(RuntimeException original, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Falló un paso posterior al error {}: {}", original.getMessage(), e.toString());
            original.addSuppressed(e);
        }
    }

    private Reservation findAccessible(CurrentUser user, UUID id) {
        Reservation reservation = findOrThrow(id);
        if (!user.canAccess(reservation.getCreatedByUserId())) {
            throw new BusinessRuleException(ErrorCode.ACCESS_DENIED,
                    "La reserva " + id + " pertenece a otro usuario");
        }
        return reservation;
    }

    private Reservation findOrThrow(UUID id) {
        return reservationRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.RESERVATION_NOT_FOUND,
                        "No existe la reserva " + id));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static BusinessRuleException duplicateActive() {
        return new BusinessRuleException(ErrorCode.DUPLICATE_ACTIVE_RESERVATION,
                "El pasajero ya tiene una reserva activa para ese vuelo");
    }
}
