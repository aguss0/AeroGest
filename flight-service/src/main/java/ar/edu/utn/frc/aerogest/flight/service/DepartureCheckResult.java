package ar.edu.utn.frc.aerogest.flight.service;

import ar.edu.utn.frc.aerogest.flight.opensky.AircraftState;
import java.util.Collection;
import java.util.Optional;

/**
 * Resultado de comparar un vuelo con lo que informa OpenSky (RN-16). Solo
 * {@link #DEPARTURE_DETECTED} cierra el vuelo; el resto no cambia su estado.
 */
public enum DepartureCheckResult {
    /** La aeronave está en el aire con el callsign del vuelo: despegó. */
    DEPARTURE_DETECTED,
    /** La aeronave está en tierra. */
    ON_GROUND,
    /** La aeronave está en el aire con otro callsign: está haciendo otro vuelo. */
    OTHER_FLIGHT,
    /** OpenSky no tiene datos actuales de la aeronave (apagada o fuera de cobertura). */
    NOT_SEEN;

    /** Estado informado por OpenSky para el icao24 del vuelo, si lo hay. */
    public static Optional<AircraftState> find(Collection<AircraftState> states, String icao24) {
        return states.stream().filter(s -> s.icao24().equalsIgnoreCase(icao24)).findFirst();
    }

    /** La regla RN-16. Exige las tres condiciones: aeronave presente, en el aire y con el mismo callsign. */
    public static DepartureCheckResult evaluate(String expectedCallsign, Optional<AircraftState> state) {
        if (state.isEmpty()) {
            return NOT_SEEN;
        }
        AircraftState s = state.get();
        if (s.onGround()) {
            return ON_GROUND;
        }
        if (s.callsign() != null && s.callsign().equalsIgnoreCase(expectedCallsign)) {
            return DEPARTURE_DETECTED;
        }
        return OTHER_FLIGHT;
    }
}
