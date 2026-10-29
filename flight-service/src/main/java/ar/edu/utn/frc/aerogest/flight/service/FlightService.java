package ar.edu.utn.frc.aerogest.flight.service;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.dto.ChangeStatusRequest;
import ar.edu.utn.frc.aerogest.flight.dto.CreateFlightRequest;
import ar.edu.utn.frc.aerogest.flight.dto.FlightResponse;
import ar.edu.utn.frc.aerogest.flight.dto.FlightSearchCriteria;
import ar.edu.utn.frc.aerogest.flight.dto.PageResponse;
import ar.edu.utn.frc.aerogest.flight.dto.UpdateFlightRequest;
import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.flight.repository.FlightRepository;
import ar.edu.utn.frc.aerogest.flight.repository.FlightSpecifications;
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
 * Casos de uso de administración de vuelos. Orquesta el repositorio y delega las reglas
 * propias del vuelo en la entidad {@link Flight}. Las operaciones de asignación de plazas
 * se agregan en la Parte 3.
 */
@Service
public class FlightService {

    static final int MAX_PAGE_SIZE = 100;

    private final FlightRepository flightRepository;
    private final Clock clock;

    public FlightService(FlightRepository flightRepository, Clock clock) {
        this.flightRepository = flightRepository;
        this.clock = clock;
    }

    @Transactional
    public FlightResponse create(CreateFlightRequest request) {
        // Verificación previa para dar un error claro...
        if (flightRepository.existsByCode(request.code())) {
            throw duplicateCode(request.code());
        }
        Flight flight = Flight.create(
                request.code(), request.origin(), request.destination(),
                request.departureAt(), request.arrivalAt(), request.capacity(), now());
        try {
            return FlightResponse.from(flightRepository.saveAndFlush(flight));
        } catch (DataIntegrityViolationException e) {
            // ...pero la garantía real es la restricción UNIQUE de la base (RN-01),
            // que también cubre dos altas simultáneas con el mismo código.
            if (isDuplicateCode(e)) {
                throw duplicateCode(request.code());
            }
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public FlightResponse getById(Long id) {
        return FlightResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public PageResponse<FlightResponse> search(FlightSearchCriteria criteria, int page, int size) {
        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by("departureAt").ascending().and(Sort.by("id").ascending()));
        Page<FlightResponse> result = flightRepository
                .findAll(FlightSpecifications.from(criteria), pageable)
                .map(FlightResponse::from);
        return PageResponse.from(result);
    }

    @Transactional
    public FlightResponse update(Long id, UpdateFlightRequest request) {
        Flight flight = findOrThrow(id);
        flight.update(request.origin(), request.destination(),
                request.departureAt(), request.arrivalAt(), request.capacity(), now());
        return FlightResponse.from(flight);
    }

    @Transactional
    public FlightResponse changeStatus(Long id, ChangeStatusRequest request) {
        Flight flight = findOrThrow(id);
        flight.changeStatus(request.status(), now());
        return FlightResponse.from(flight);
    }

    private Flight findOrThrow(Long id) {
        return flightRepository.findById(id)
                .orElseThrow(() -> new BusinessRuleException(ErrorCode.FLIGHT_NOT_FOUND,
                        "No existe el vuelo con id " + id));
    }

    private Instant now() {
        return Instant.now(clock);
    }

    private static BusinessRuleException duplicateCode(String code) {
        return new BusinessRuleException(ErrorCode.DUPLICATE_FLIGHT_CODE,
                "Ya existe un vuelo con el código " + code);
    }

    private static boolean isDuplicateCode(DataIntegrityViolationException e) {
        String message = e.getMostSpecificCause().getMessage();
        return message != null && message.contains("uq_flights_code");
    }
}
