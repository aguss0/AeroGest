package ar.edu.utn.frc.aerogest.flight.domain;

import static ar.edu.utn.frc.aerogest.flight.domain.FlightStatus.CANCELLED;
import static ar.edu.utn.frc.aerogest.flight.domain.FlightStatus.CLOSED;
import static ar.edu.utn.frc.aerogest.flight.domain.FlightStatus.COMPLETED;
import static ar.edu.utn.frc.aerogest.flight.domain.FlightStatus.SCHEDULED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RN-08: transiciones de estado del vuelo. */
class FlightStatusTest {

    @Test
    void scheduledSoloPuedePasarAClosedOCancelled() {
        assertThat(SCHEDULED.allowedTransitions()).containsExactlyInAnyOrder(CLOSED, CANCELLED);
        assertThat(SCHEDULED.canTransitionTo(COMPLETED)).isFalse();
    }

    @Test
    void closedPuedeReabrirseCancelarseOCompletarse() {
        assertThat(CLOSED.allowedTransitions()).containsExactlyInAnyOrder(SCHEDULED, CANCELLED, COMPLETED);
    }

    @Test
    void cancelledYCompletedSonTerminales() {
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(COMPLETED.isTerminal()).isTrue();
        assertThat(SCHEDULED.isTerminal()).isFalse();
        assertThat(CLOSED.isTerminal()).isFalse();
    }

    @Test
    void ningunEstadoPuedePasarASiMismo() {
        for (FlightStatus status : FlightStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }
}
