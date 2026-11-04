package ar.edu.utn.frc.aerogest.flight.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** Reglas del dominio de vuelos (RN-02, RN-03, RN-04, RN-08, RN-11, RN-12, RN-13). */
class FlightTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant DEPARTURE = NOW.plus(Duration.ofDays(10));
    private static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(2));

    private Flight validFlight() {
        return Flight.create("AG1001", "COR", "EZE", DEPARTURE, ARRIVAL, 100, NOW);
    }

    private void assertRule(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    @Test
    void creaVueloValidoEnEstadoScheduledSinPlazasOcupadas() {
        Flight flight = validFlight();

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
        assertThat(flight.getSeatsTaken()).isZero();
        assertThat(flight.availableSeats()).isEqualTo(100);
        assertThat(flight.getCode()).isEqualTo("AG1001");
    }

    @Test
    void rechazaLlegadaAnteriorOIgualALaSalida_RN02() {
        assertRule(() -> Flight.create("AG1", "COR", "EZE", DEPARTURE, DEPARTURE.minusSeconds(1), 100, NOW),
                ErrorCode.INVALID_SCHEDULE);
        assertRule(() -> Flight.create("AG1", "COR", "EZE", DEPARTURE, DEPARTURE, 100, NOW),
                ErrorCode.INVALID_SCHEDULE);
    }

    @Test
    void rechazaCapacidadCeroONegativa_RN03() {
        assertRule(() -> Flight.create("AG1", "COR", "EZE", DEPARTURE, ARRIVAL, 0, NOW),
                ErrorCode.INVALID_CAPACITY);
        assertRule(() -> Flight.create("AG1", "COR", "EZE", DEPARTURE, ARRIVAL, -5, NOW),
                ErrorCode.INVALID_CAPACITY);
    }

    @Test
    void rechazaOrigenIgualADestino_RN11() {
        assertRule(() -> Flight.create("AG1", "COR", "COR", DEPARTURE, ARRIVAL, 100, NOW),
                ErrorCode.INVALID_ROUTE);
    }

    @Test
    void rechazaSalidaEnElPasado_RN12() {
        assertRule(() -> Flight.create("AG1", "COR", "EZE", NOW.minusSeconds(60), ARRIVAL, 100, NOW),
                ErrorCode.DEPARTURE_IN_PAST);
    }

    @Test
    void permiteCambioDeEstadoValido() {
        Flight flight = validFlight();

        flight.changeStatus(FlightStatus.CLOSED, NOW.plusSeconds(5));

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CLOSED);
        assertThat(flight.getUpdatedAt()).isEqualTo(NOW.plusSeconds(5));
    }

    @Test
    void rechazaCambioDeEstadoInvalido_RN08() {
        Flight flight = validFlight();

        assertRule(() -> flight.changeStatus(FlightStatus.COMPLETED, NOW), ErrorCode.INVALID_STATUS_TRANSITION);
        assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void unVueloCanceladoNoSePuedeReabrirNiModificar() {
        Flight flight = validFlight();
        flight.changeStatus(FlightStatus.CANCELLED, NOW);

        assertRule(() -> flight.changeStatus(FlightStatus.SCHEDULED, NOW), ErrorCode.INVALID_STATUS_TRANSITION);
        assertRule(() -> flight.update("COR", "EZE", DEPARTURE, ARRIVAL, 120, NOW), ErrorCode.FLIGHT_NOT_MODIFIABLE);
    }

    @Test
    void soloEsReservableSiEstaScheduledYNoSalio_RN04() {
        Flight flight = validFlight();
        assertThat(flight.isReservable(NOW)).isTrue();
        assertThat(flight.isReservable(DEPARTURE.plusSeconds(1))).isFalse();

        flight.changeStatus(FlightStatus.CLOSED, NOW);
        assertThat(flight.isReservable(NOW)).isFalse();

        flight.changeStatus(FlightStatus.CANCELLED, NOW);
        assertThat(flight.isReservable(NOW)).isFalse();
    }

    @Test
    void noPermiteReducirLaCapacidadPorDebajoDeLasPlazasOcupadas_RN13() {
        Flight flight = validFlight();
        ReflectionTestUtils.setField(flight, "seatsTaken", 40);

        assertRule(() -> flight.update("COR", "EZE", DEPARTURE, ARRIVAL, 39, NOW), ErrorCode.INVALID_CAPACITY);

        flight.update("COR", "EZE", DEPARTURE, ARRIVAL, 40, NOW);
        assertThat(flight.getCapacity()).isEqualTo(40);
        assertThat(flight.availableSeats()).isZero();
    }

    // ---------- aeronave y salida real (RN-16) ----------

    @Test
    void asignaLaAeronaveNormalizandoCallsignEIcao24() {
        Flight flight = validFlight();

        flight.assignAircraft(" arg1132 ", "E0659A", NOW);

        assertThat(flight.getCallsign()).isEqualTo("ARG1132");
        assertThat(flight.getAircraftIcao24()).isEqualTo("e0659a");
        assertThat(flight.isTrackable()).isTrue();
    }

    @Test
    void callsignEIcao24VanJuntos_RN16() {
        assertRule(() -> validFlight().assignAircraft("ARG1132", null, NOW), ErrorCode.INVALID_AIRCRAFT_REFERENCE);
        assertRule(() -> validFlight().assignAircraft(null, "e0659a", NOW), ErrorCode.INVALID_AIRCRAFT_REFERENCE);
        assertRule(() -> validFlight().assignAircraft(" ", "e0659a", NOW), ErrorCode.INVALID_AIRCRAFT_REFERENCE);
    }

    @Test
    void sinAeronaveNoSeVerifica() {
        Flight flight = validFlight();
        flight.assignAircraft(null, null, NOW);

        assertThat(flight.getCallsign()).isNull();
        assertThat(flight.isTrackable()).isFalse();
    }

    @Test
    void cambiarDeAeronaveDescartaLaUltimaVerificacion() {
        Flight flight = validFlight();
        flight.assignAircraft("ARG1132", "e0659a", NOW);
        flight.recordDepartureCheck(NOW);

        flight.assignAircraft("ARG1132", "e0659a", NOW);
        assertThat(flight.getDepartureCheckedAt()).isEqualTo(NOW);

        flight.assignAircraft("ARG1520", "e06442", NOW);
        assertThat(flight.getDepartureCheckedAt()).isNull();
    }

    @Test
    void elDespegueDetectadoCierraElVueloAunqueLaSalidaProgramadaSeaFutura_RN16() {
        Flight flight = validFlight();
        flight.assignAircraft("ARG1132", "e0659a", NOW);

        flight.registerDetectedDeparture(NOW);

        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CLOSED);
        assertThat(flight.getDepartureDetectedAt()).isEqualTo(NOW);
        assertThat(flight.getDepartureCheckedAt()).isEqualTo(NOW);
        assertThat(flight.isReservable(NOW)).isFalse();
        assertThat(flight.isTrackable()).isFalse();
    }

    @Test
    void soloSeCierraPorDespegueUnVueloScheduled() {
        Flight flight = validFlight();
        flight.assignAircraft("ARG1132", "e0659a", NOW);
        flight.changeStatus(FlightStatus.CANCELLED, NOW);

        assertRule(() -> flight.registerDetectedDeparture(NOW), ErrorCode.FLIGHT_NOT_TRACKABLE);
        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
    }
}
