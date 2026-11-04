package ar.edu.utn.frc.aerogest.reservation.service;

import ar.edu.utn.frc.aerogest.reservation.domain.Passenger;
import ar.edu.utn.frc.aerogest.reservation.dto.CreatePassengerRequest;
import ar.edu.utn.frc.aerogest.common.web.PageResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.PassengerResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.UpdatePassengerRequest;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.repository.PassengerRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Casos de uso de pasajeros. Un USER gestiona solo los pasajeros que registró; un ADMIN, todos (RN-09).
 * No hay baja: las reservas referencian al pasajero.
 */
@Service
public class PassengerService {

    static final int MAX_PAGE_SIZE = 100;

    private final PassengerRepository passengerRepository;
    private final Clock clock;

    public PassengerService(PassengerRepository passengerRepository, Clock clock) {
        this.passengerRepository = passengerRepository;
        this.clock = clock;
    }

    @Transactional
    public PassengerResponse register(CurrentUser user, CreatePassengerRequest request) {
        Passenger passenger = Passenger.register(user.userId(), request.firstName(), request.lastName(),
                request.documentType(), request.documentNumber(), now());
        // Verificación previa con el número ya normalizado, para dar un error claro...
        if (passengerRepository.existsByDocumentTypeAndDocumentNumber(
                passenger.getDocumentType(), passenger.getDocumentNumber())) {
            throw duplicateDocument();
        }
        try {
            return PassengerResponse.from(passengerRepository.saveAndFlush(passenger));
        } catch (DataIntegrityViolationException e) {
            // ...pero la garantía real es la restricción UNIQUE de la base (RN-14),
            // que también cubre dos altas simultáneas con el mismo documento.
            if (violates(e, "uq_passengers_document")) {
                throw duplicateDocument();
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public PassengerResponse getById(CurrentUser user, Long id) {
        return PassengerResponse.from(findAccessible(user, id));
    }

    @Transactional(readOnly = true)
    public PageResponse<PassengerResponse> list(CurrentUser user, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("lastName", "firstName", "id"));
        Page<Passenger> result = user.admin()
                ? passengerRepository.findAll(pageable)
                : passengerRepository.findByOwnerUserId(user.userId(), pageable);
        return PageResponse.from(result.map(PassengerResponse::from));
    }

    @Transactional
    public PassengerResponse rename(CurrentUser user, Long id, UpdatePassengerRequest request) {
        Passenger passenger = findAccessible(user, id);
        passenger.rename(request.firstName(), request.lastName(), now());
        return PassengerResponse.from(passenger);
    }

    /** Busca un pasajero y verifica que el usuario pueda operar con él. Lo usa también ReservationService. */
    public Passenger findAccessible(CurrentUser user, Long id) {
        Passenger passenger = passengerRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.PASSENGER_NOT_FOUND,
                        "No existe el pasajero con id " + id));
        if (!user.canAccess(passenger.getOwnerUserId())) {
            throw new BusinessRuleException(ErrorCode.ACCESS_DENIED,
                    "El pasajero " + id + " pertenece a otro usuario");
        }
        return passenger;
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static BusinessRuleException duplicateDocument() {
        return new BusinessRuleException(ErrorCode.DUPLICATE_PASSENGER_DOCUMENT,
                "Ya existe un pasajero registrado con ese documento");
    }

    /** true si la violación de integridad corresponde a la restricción indicada. */
    static boolean violates(DataIntegrityViolationException e, String constraint) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains(constraint);
    }
}
