package ar.edu.utn.frc.aerogest.reservation.domain;

import static ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus.CANCELLED;
import static ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus.CONFIRMED;
import static ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus.PENDING;
import static ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus.REJECTED;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** RN-08: transiciones de estado de la reserva. */
class ReservationStatusTest {

    @Test
    void pendingSoloPuedeConfirmarseORechazarse() {
        assertThat(PENDING.allowedTransitions()).containsExactlyInAnyOrder(CONFIRMED, REJECTED);
        assertThat(PENDING.canTransitionTo(CANCELLED)).isFalse();
    }

    @Test
    void confirmedSoloPuedeCancelarse() {
        assertThat(CONFIRMED.allowedTransitions()).containsExactly(CANCELLED);
    }

    @Test
    void cancelledYRejectedSonTerminales() {
        assertThat(CANCELLED.isTerminal()).isTrue();
        assertThat(REJECTED.isTerminal()).isTrue();
        assertThat(PENDING.isTerminal()).isFalse();
        assertThat(CONFIRMED.isTerminal()).isFalse();
    }

    @Test
    void ningunEstadoPuedePasarASiMismo() {
        for (ReservationStatus status : ReservationStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }

    @Test
    void soloPendingYConfirmedSonActivas_RN06() {
        assertThat(PENDING.isActive()).isTrue();
        assertThat(CONFIRMED.isActive()).isTrue();
        assertThat(CANCELLED.isActive()).isFalse();
        assertThat(REJECTED.isActive()).isFalse();
    }
}
