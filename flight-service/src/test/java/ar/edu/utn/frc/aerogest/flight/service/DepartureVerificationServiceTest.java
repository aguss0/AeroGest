package ar.edu.utn.frc.aerogest.flight.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

/** Manejo de créditos agotados (429) y del vuelo que cambia mientras se consulta OpenSky. */
class DepartureVerificationServiceTest {

    private static final Instant T0 = Instant.parse("2026-11-04T12:00:00Z");

    private final FlightRepository repository = mock(FlightRepository.class);
    private final OpenSkyClient openSky = mock(OpenSkyClient.class);
    private final MutableClock clock = new MutableClock(T0);
    private DepartureVerificationService service;
    private Flight flight;

    /** Reloj que la prueba puede adelantar. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() {
        OpenSkyProperties props = new OpenSkyProperties(true, null, null, null,
                Duration.ofMinutes(5), null, null, null);
        service = new DepartureVerificationService(repository, openSky, props, clock,
                mock(PlatformTransactionManager.class));
        flight = Flight.create("AG1", "COR", "EZE", T0.plus(Duration.ofHours(1)), T0.plus(Duration.ofHours(3)), 10,
                T0.minus(Duration.ofDays(1)));
        flight.assignAircraft("ARG1132", "e0659a", T0);
        ReflectionTestUtils.setField(flight, "id", 7L);
        when(repository.findTrackingCandidates(any(), any(), any(), any())).thenReturn(List.of(flight));
        when(repository.findById(7L)).thenReturn(Optional.of(flight));
    }

    @Test
    void con429SeSalteanLasCorridasHastaElTiempoIndicado() {
        when(openSky.statesOf(anyCollection())).thenThrow(new OpenSkyException(ErrorCode.OPENSKY_RATE_LIMITED,
                "sin créditos", Duration.ofMinutes(30), null));

        assertThat(service.verifyUpcoming().skipped()).isFalse();
        clock.advance(Duration.ofMinutes(29));
        assertThat(service.verifyUpcoming().skipped()).isTrue();
        verify(openSky, times(1)).statesOf(anyCollection());

        // Bloqueado también para la verificación manual, sin gastar otra consulta
        assertThatThrownBy(() -> service.checkNow(7L))
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.OPENSKY_RATE_LIMITED));
        verify(openSky, times(1)).statesOf(anyCollection());

        clock.advance(Duration.ofMinutes(2));
        assertThat(service.verifyUpcoming().skipped()).isFalse();
        verify(openSky, times(2)).statesOf(anyCollection());
    }

    @Test
    void con429SinTiempoIndicadoEsperaUnIntervalo() {
        when(openSky.statesOf(anyCollection())).thenThrow(new OpenSkyException(ErrorCode.OPENSKY_RATE_LIMITED,
                "sin créditos", null, null));

        service.verifyUpcoming();
        clock.advance(Duration.ofMinutes(4));
        assertThat(service.verifyUpcoming().skipped()).isTrue();
        clock.advance(Duration.ofMinutes(2));
        assertThat(service.verifyUpcoming().skipped()).isFalse();
    }

    @Test
    void siElVueloCambioDeAeronaveDuranteLaConsultaNoSeCierra() {
        when(openSky.statesOf(anyCollection())).thenAnswer(inv -> {
            // Mientras se consultaba, el admin asignó otra aeronave
            flight.assignAircraft("ARG1520", "e06442", T0);
            return List.of(new AircraftState("e0659a", "ARG1132", false, T0));
        });

        DepartureVerificationService.RunSummary summary = service.verifyUpcoming();

        assertThat(summary.closed()).isZero();
        assertThat(flight.getStatus()).isEqualTo(FlightStatus.SCHEDULED);
    }

    @Test
    void siElVueloYaNoEstaProgramadoNoSeTocaAunqueHayaDespegado() {
        when(openSky.statesOf(anyCollection())).thenAnswer(inv -> {
            flight.changeStatus(FlightStatus.CANCELLED, T0);
            return List.of(new AircraftState("e0659a", "ARG1132", false, T0));
        });

        assertThat(service.verifyUpcoming().closed()).isZero();
        assertThat(flight.getStatus()).isEqualTo(FlightStatus.CANCELLED);
    }
}
