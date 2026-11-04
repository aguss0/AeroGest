package ar.edu.utn.frc.aerogest.flight.service;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import ar.edu.utn.frc.aerogest.flight.dto.DepartureCheckResponse;
import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.flight.opensky.AircraftState;
import ar.edu.utn.frc.aerogest.flight.opensky.OpenSkyClient;
import ar.edu.utn.frc.aerogest.flight.opensky.OpenSkyException;
import ar.edu.utn.frc.aerogest.flight.opensky.OpenSkyProperties;
import ar.edu.utn.frc.aerogest.flight.repository.FlightRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Verificación de la salida real de los vuelos con OpenSky Network (RN-16, ADR 0006).
 *
 * La consulta a OpenSky queda fuera de toda transacción: primero se leen los candidatos, después
 * se consulta (puede tardar o fallar) y recién entonces se aplica el resultado a cada vuelo en una
 * transacción corta que vuelve a leerlo. Si mientras tanto el vuelo cambió (otra aeronave, otro
 * estado o una edición concurrente), no se toca.
 *
 * Principio: nunca se cierra un vuelo sin una respuesta válida que muestre la aeronave en el aire
 * con el callsign del vuelo. Ante cualquier falla no cambia nada y sigue rigiendo RN-04 por horario.
 */
@Service
public class DepartureVerificationService {

    private static final Logger log = LoggerFactory.getLogger(DepartureVerificationService.class);

    private final FlightRepository flightRepository;
    private final OpenSkyClient openSky;
    private final OpenSkyProperties properties;
    private final Clock clock;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    /** Hasta cuándo no se consulta OpenSky porque informó que se agotaron los créditos (429). */
    private final AtomicReference<Instant> blockedUntil = new AtomicReference<>(Instant.MIN);

    public DepartureVerificationService(FlightRepository flightRepository, OpenSkyClient openSky,
                                        OpenSkyProperties properties, Clock clock,
                                        PlatformTransactionManager transactionManager) {
        this.flightRepository = flightRepository;
        this.openSky = openSky;
        this.properties = properties;
        this.clock = clock;
        this.tx = new TransactionTemplate(transactionManager);
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    /** Resumen de una corrida del job. */
    public record RunSummary(int candidates, int closed, boolean skipped) {
        static RunSummary skippedRun() {
            return new RunSummary(0, 0, true);
        }
    }

    private record Candidate(Long id, String icao24, String callsign) {
        static Candidate of(Flight f) {
            return new Candidate(f.getId(), f.getAircraftIcao24(), f.getCallsign());
        }
    }

    // ------------------------------------------------------------------ job

    /** Verifica en una sola consulta todos los vuelos cuya salida programada está dentro de la ventana. */
    public RunSummary verifyUpcoming() {
        Instant now = now();
        if (isBlocked(now)) {
            log.info("OpenSky: corrida salteada, créditos agotados hasta {}", blockedUntil.get());
            return RunSummary.skippedRun();
        }
        List<Candidate> candidates = readOnlyTx.execute(status -> flightRepository.findTrackingCandidates(
                        FlightStatus.SCHEDULED, now.minus(properties.lookBehind()), now.plus(properties.lookAhead()),
                        PageRequest.of(0, properties.batchSize()))
                .stream().map(Candidate::of).toList());
        if (candidates == null || candidates.isEmpty()) {
            return new RunSummary(0, 0, false);
        }

        List<AircraftState> states;
        try {
            states = openSky.statesOf(candidates.stream().map(Candidate::icao24).distinct().toList());
        } catch (OpenSkyException e) {
            registerFailure(e, now);
            log.warn("OpenSky: no se pudo verificar {} vuelos ({}: {}). No se cambia nada; rige RN-04 por horario",
                    candidates.size(), e.getErrorCode(), e.getMessage());
            return new RunSummary(candidates.size(), 0, false);
        }

        int closed = 0;
        for (Candidate candidate : candidates) {
            Optional<AircraftState> state = DepartureCheckResult.find(states, candidate.icao24());
            DepartureCheckResult result = DepartureCheckResult.evaluate(candidate.callsign(), state);
            try {
                if (apply(candidate, result, now) && result == DepartureCheckResult.DEPARTURE_DETECTED) {
                    closed++;
                }
            } catch (OptimisticLockingFailureException e) {
                log.info("OpenSky: el vuelo {} se modificó durante la verificación; se revisa en la próxima corrida",
                        candidate.id());
            }
        }
        log.info("OpenSky: {} vuelos verificados, {} cerrados por despegue", candidates.size(), closed);
        return new RunSummary(candidates.size(), closed, false);
    }

    // ------------------------------------------------------------ manual

    /** Verificación inmediata de un vuelo (ADMIN). Las fallas de OpenSky se informan como error HTTP. */
    public DepartureCheckResponse checkNow(Long flightId) {
        Instant now = now();
        Candidate candidate = readOnlyTx.execute(status -> {
            Flight flight = flightRepository.findById(flightId)
                    .orElseThrow(() -> new BusinessRuleException(ErrorCode.FLIGHT_NOT_FOUND,
                            "No existe el vuelo con id " + flightId));
            if (flight.getAircraftIcao24() == null) {
                throw new BusinessRuleException(ErrorCode.FLIGHT_NOT_TRACKABLE,
                        "El vuelo no tiene aeronave asignada (callsign e icao24)");
            }
            if (flight.getStatus() != FlightStatus.SCHEDULED) {
                throw new BusinessRuleException(ErrorCode.FLIGHT_NOT_TRACKABLE,
                        "Solo se verifica la salida de un vuelo SCHEDULED; está " + flight.getStatus());
            }
            return Candidate.of(flight);
        });
        if (isBlocked(now)) {
            throw new BusinessRuleException(ErrorCode.OPENSKY_RATE_LIMITED,
                    "Se agotaron los créditos de OpenSky. Reintentá después de " + blockedUntil.get());
        }

        List<AircraftState> states;
        try {
            states = openSky.statesOf(List.of(candidate.icao24()));
        } catch (OpenSkyException e) {
            registerFailure(e, now);
            log.warn("OpenSky: falló la verificación del vuelo {} ({}: {})", flightId, e.getErrorCode(), e.getMessage());
            throw new BusinessRuleException(e.getErrorCode(), e.getMessage() + ". El vuelo no cambió de estado");
        }
        Optional<AircraftState> state = DepartureCheckResult.find(states, candidate.icao24());
        DepartureCheckResult result = DepartureCheckResult.evaluate(candidate.callsign(), state);
        apply(candidate, result, now);

        Flight flight = readOnlyTx.execute(status -> flightRepository.findById(flightId).orElseThrow());
        return new DepartureCheckResponse(flightId, flight.getCallsign(), flight.getAircraftIcao24(), result,
                flight.getStatus(),
                state.map(AircraftState::callsign).orElse(null),
                state.map(AircraftState::onGround).orElse(null),
                state.map(AircraftState::lastContact).orElse(null),
                flight.getDepartureCheckedAt(), flight.getDepartureDetectedAt());
    }

    // ------------------------------------------------------------ común

    /**
     * Aplica el resultado en una transacción corta, sobre el vuelo releído. Devuelve false si el
     * vuelo ya no corresponde a lo consultado (cambió de aeronave o de estado): no se toca.
     */
    private boolean apply(Candidate candidate, DepartureCheckResult result, Instant now) {
        Boolean applied = tx.execute(status -> {
            Optional<Flight> current = flightRepository.findById(candidate.id());
            if (current.isEmpty()) {
                return false;
            }
            Flight flight = current.get();
            if (!flight.isTrackable()
                    || !candidate.icao24().equals(flight.getAircraftIcao24())
                    || !candidate.callsign().equals(flight.getCallsign())) {
                return false;
            }
            if (result == DepartureCheckResult.DEPARTURE_DETECTED) {
                flight.registerDetectedDeparture(now);
                log.info("RN-16: vuelo {} ({}) cerrado: OpenSky muestra {} en el aire",
                        flight.getId(), flight.getCode(), flight.getCallsign());
            } else {
                flight.recordDepartureCheck(now);
            }
            flightRepository.flush();
            return true;
        });
        return Boolean.TRUE.equals(applied);
    }

    private void registerFailure(OpenSkyException e, Instant now) {
        if (e.getErrorCode() == ErrorCode.OPENSKY_RATE_LIMITED) {
            Duration wait = e.getRetryAfter() != null ? e.getRetryAfter() : properties.interval();
            blockedUntil.set(now.plus(wait));
        }
    }

    private boolean isBlocked(Instant now) {
        return now.isBefore(blockedUntil.get());
    }

    private Instant now() {
        return Instant.now(clock);
    }
}
