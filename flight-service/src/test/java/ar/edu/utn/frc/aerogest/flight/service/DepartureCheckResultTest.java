package ar.edu.utn.frc.aerogest.flight.service;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.utn.frc.aerogest.flight.opensky.AircraftState;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** RN-16: solo se considera despegado si la aeronave está presente, en el aire y con el mismo callsign. */
class DepartureCheckResultTest {

    private static Optional<AircraftState> state(String callsign, boolean onGround) {
        return Optional.of(new AircraftState("e0659a", callsign, onGround, null));
    }

    @Test
    void enElAireConElMismoCallsignDespego() {
        assertThat(DepartureCheckResult.evaluate("ARG1132", state("ARG1132", false)))
                .isEqualTo(DepartureCheckResult.DEPARTURE_DETECTED);
        assertThat(DepartureCheckResult.evaluate("ARG1132", state("arg1132", false)))
                .isEqualTo(DepartureCheckResult.DEPARTURE_DETECTED);
    }

    @Test
    void enTierraNoDespego() {
        assertThat(DepartureCheckResult.evaluate("ARG1132", state("ARG1132", true)))
                .isEqualTo(DepartureCheckResult.ON_GROUND);
    }

    @Test
    void enElAireConOtroCallsignEsOtroVuelo() {
        assertThat(DepartureCheckResult.evaluate("ARG1132", state("ARG1520", false)))
                .isEqualTo(DepartureCheckResult.OTHER_FLIGHT);
        assertThat(DepartureCheckResult.evaluate("ARG1132", state(null, false)))
                .isEqualTo(DepartureCheckResult.OTHER_FLIGHT);
    }

    @Test
    void sinDatosDeLaAeronaveNoHayEvidencia() {
        assertThat(DepartureCheckResult.evaluate("ARG1132", Optional.empty()))
                .isEqualTo(DepartureCheckResult.NOT_SEEN);
    }

    @Test
    void buscaElEstadoPorIcao24SinDistinguirMayusculas() {
        List<AircraftState> states = List.of(new AircraftState("e0659a", "ARG1132", false, null));

        assertThat(DepartureCheckResult.find(states, "E0659A")).isPresent();
        assertThat(DepartureCheckResult.find(states, "e06442")).isEmpty();
    }
}
