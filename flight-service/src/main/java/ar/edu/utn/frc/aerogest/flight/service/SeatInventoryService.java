package ar.edu.utn.frc.aerogest.flight.service;

import ar.edu.utn.frc.aerogest.flight.domain.AllocationStatus;
import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import ar.edu.utn.frc.aerogest.flight.domain.SeatAllocation;
import ar.edu.utn.frc.aerogest.flight.dto.AllocationResponse;
import ar.edu.utn.frc.aerogest.flight.dto.FlightAvailabilityResponse;
import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.flight.repository.FlightRepository;
import ar.edu.utn.frc.aerogest.flight.repository.SeatAllocationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inventario de plazas: disponibilidad, asignación y liberación. Es la parte de Flight Service
 * que usa Reservation Service durante el proceso de reserva.
 *
 * Garantías:
 *  - RN-05 (no sobreventa): la plaza se ocupa con un UPDATE atómico condicionado (ver FlightRepository).
 *  - RN-15 (idempotencia): cada reserva tiene como máximo un registro en seat_allocations, por lo que
 *    repetir una asignación o una liberación es seguro. Esto permite reintentos ante timeouts.
 */
@Service
public class SeatInventoryService {

    private final FlightRepository flightRepository;
    private final SeatAllocationRepository allocationRepository;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public SeatInventoryService(FlightRepository flightRepository,
                                SeatAllocationRepository allocationRepository,
                                Clock clock,
                                PlatformTransactionManager transactionManager) {
        this.flightRepository = flightRepository;
        this.allocationRepository = allocationRepository;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public FlightAvailabilityResponse getAvailability(Long flightId) {
        Flight flight = findFlightOrThrow(flightId);
        return FlightAvailabilityResponse.from(flight, Instant.now(clock));
    }

    public AllocationResult allocate(Long flightId, UUID reservationId) {
        return inTransactionWithOneRetry(() -> doAllocate(flightId, reservationId));
    }

    public void release(Long flightId, UUID reservationId) {
        inTransactionWithOneRetry(() -> {
            doRelease(flightId, reservationId);
            return null;
        });
    }

    private AllocationResult doAllocate(Long flightId, UUID reservationId) {
        Instant now = Instant.now(clock);

        Optional<SeatAllocation> existing = allocationRepository.findById(reservationId);
        if (existing.isPresent()) {
            SeatAllocation allocation = existing.get();
            if (!allocation.getFlightId().equals(flightId)) {
                throw conflict("La reserva ya está asociada a otro vuelo");
            }
            if (!allocation.isAllocated()) {
                throw conflict("La plaza de esta reserva ya fue liberada");
            }
            // Reintento de una asignación ya hecha: se responde lo mismo, sin ocupar otra plaza
            return new AllocationResult(response(flightId, reservationId), false);
        }

        int updated = flightRepository.allocateSeat(flightId, FlightStatus.SCHEDULED, now);
        if (updated == 0) {
            throw explainAllocationFailure(flightId, now);
        }
        allocationRepository.saveAndFlush(SeatAllocation.allocate(reservationId, flightId, now));
        return new AllocationResult(response(flightId, reservationId), true);
    }

    private void doRelease(Long flightId, UUID reservationId) {
        Instant now = Instant.now(clock);

        Optional<SeatAllocation> existing = allocationRepository.findById(reservationId);
        if (existing.isEmpty()) {
            // Nunca hubo asignación: se deja una ficha para rechazar una asignación tardía
            if (!flightRepository.existsById(flightId)) {
                throw notFound(flightId);
            }
            allocationRepository.saveAndFlush(SeatAllocation.releasedMarker(reservationId, flightId, now));
            return;
        }

        SeatAllocation allocation = existing.get();
        if (!allocation.getFlightId().equals(flightId)) {
            throw conflict("La reserva está asociada a otro vuelo");
        }
        if (allocation.isAllocated()) {
            // Cambio de estado atómico: si dos liberaciones llegan a la vez, solo una devuelve la plaza (RN-07)
            int marked = allocationRepository.markReleased(
                    reservationId, AllocationStatus.ALLOCATED, AllocationStatus.RELEASED, now);
            if (marked == 1) {
                int updated = flightRepository.releaseSeat(flightId, now);
                if (updated == 0) {
                    // No debería pasar: indicaría un contador inconsistente. Se revierte todo.
                    throw new IllegalStateException("El contador de plazas del vuelo " + flightId + " ya estaba en 0");
                }
            }
        }
        // Si ya estaba liberada no se hace nada: la operación es idempotente
    }

    /** Si el UPDATE atómico no afectó filas, se averigua el motivo para dar un error claro. */
    private BusinessRuleException explainAllocationFailure(Long flightId, Instant now) {
        Flight flight = findFlightOrThrow(flightId);
        if (!flight.isReservable(now)) {
            return new BusinessRuleException(ErrorCode.FLIGHT_NOT_RESERVABLE,
                    "El vuelo no admite reservas nuevas (estado " + flight.getStatus() + ")");
        }
        return new BusinessRuleException(ErrorCode.NO_AVAILABILITY,
                "El vuelo no tiene plazas disponibles");
    }

    private AllocationResponse response(Long flightId, UUID reservationId) {
        Flight flight = findFlightOrThrow(flightId);
        return new AllocationResponse(reservationId, flightId, AllocationStatus.ALLOCATED, flight.availableSeats());
    }

    /**
     * Ejecuta la operación en una transacción. Si dos solicitudes con el mismo reservationId
     * llegan a la vez, la segunda choca con la clave primaria de seat_allocations y su
     * transacción se revierte entera (incluida la plaza que había ocupado). Se reintenta una vez:
     * ahora encuentra el registro de la primera y responde de forma idempotente.
     */
    private <T> T inTransactionWithOneRetry(Supplier<T> operation) {
        try {
            return transaction.execute(status -> operation.get());
        } catch (DataIntegrityViolationException e) {
            return transaction.execute(status -> operation.get());
        }
    }

    private Flight findFlightOrThrow(Long flightId) {
        return flightRepository.findById(flightId).orElseThrow(() -> notFound(flightId));
    }

    private static BusinessRuleException notFound(Long flightId) {
        return new BusinessRuleException(ErrorCode.FLIGHT_NOT_FOUND, "No existe el vuelo con id " + flightId);
    }

    private static BusinessRuleException conflict(String message) {
        return new BusinessRuleException(ErrorCode.ALLOCATION_CONFLICT, message);
    }
}
