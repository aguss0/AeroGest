package ar.edu.utn.frc.aerogest.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Reglas del dominio de reservas (RN-07, RN-08, RN-09). */
class ReservationTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final Instant LATER = NOW.plusSeconds(30);

    private Reservation newReservation() {
        return Reservation.create(7L, 42L, "user-1", NOW);
    }

    private void assertInvalidState(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_RESERVATION_STATE));
    }

    @Test
    void unaReservaNuevaNaceEnPendingConUnIdPropio() {
        Reservation first = newReservation();
        Reservation second = newReservation();

        assertThat(first.getStatus()).isEqualTo(ReservationStatus.PENDING);
        assertThat(first.getId()).isNotNull().isNotEqualTo(second.getId());
        assertThat(first.getPassengerId()).isEqualTo(7L);
        assertThat(first.getFlightId()).isEqualTo(42L);
    }

    @Test
    void sePuedeConfirmarUnaReservaPendiente() {
        Reservation reservation = newReservation();

        reservation.confirm(LATER);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(reservation.getUpdatedAt()).isEqualTo(LATER);
    }

    @Test
    void sePuedeRechazarUnaReservaPendiente() {
        Reservation reservation = newReservation();

        reservation.reject(LATER);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.REJECTED);
    }

    @Test
    void sePuedeCancelarUnaReservaConfirmada_RN07() {
        Reservation reservation = newReservation();
        reservation.confirm(NOW);

        reservation.cancel(LATER);

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void noSePuedeCancelarDosVeces_RN07() {
        Reservation reservation = newReservation();
        reservation.confirm(NOW);
        reservation.cancel(NOW);

        assertInvalidState(() -> reservation.cancel(LATER));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    }

    @Test
    void noSePuedeCancelarUnaReservaPendienteNiRechazada_RN08() {
        Reservation pending = newReservation();
        assertInvalidState(() -> pending.cancel(NOW));

        Reservation rejected = newReservation();
        rejected.reject(NOW);
        assertInvalidState(() -> rejected.cancel(NOW));
    }

    @Test
    void unaReservaCanceladaNoSeReactiva_RN08() {
        Reservation reservation = newReservation();
        reservation.confirm(NOW);
        reservation.cancel(NOW);

        assertInvalidState(() -> reservation.confirm(LATER));
        assertInvalidState(() -> reservation.reject(LATER));
    }

    @Test
    void unaReservaConfirmadaNoSePuedeConfirmarNiRechazar() {
        Reservation reservation = newReservation();
        reservation.confirm(NOW);

        assertInvalidState(() -> reservation.confirm(LATER));
        assertInvalidState(() -> reservation.reject(LATER));
    }

    @Test
    void verificarSiSePuedeCancelarNoModificaLaReserva() {
        Reservation confirmed = newReservation();
        confirmed.confirm(NOW);
        confirmed.checkCancellable();
        assertThat(confirmed.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);

        Reservation pending = newReservation();
        assertInvalidState(pending::checkCancellable);
        assertThat(pending.getStatus()).isEqualTo(ReservationStatus.PENDING);
    }

    @Test
    void sabeAQuienPertenece_RN09() {
        Reservation reservation = newReservation();

        assertThat(reservation.isOwnedBy("user-1")).isTrue();
        assertThat(reservation.isOwnedBy("user-2")).isFalse();
    }
}
