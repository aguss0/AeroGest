package ar.edu.utn.frc.aerogest.reservation.domain;

import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Reserva de un pasajero en un vuelo. Nace en PENDING y solo pasa a CONFIRMED cuando Flight Service
 * confirma la plaza (RN-10). El id lo genera la aplicación porque se envía a Flight Service como clave
 * de idempotencia antes de confirmar. flightId es una referencia lógica: el vuelo vive en otro servicio.
 *
 * El campo @Version evita que dos operaciones simultáneas (por ejemplo, dos cancelaciones) pisen
 * el estado: una gana y la otra recibe un conflicto.
 */
@Entity
@Table(name = "reservations")
public class Reservation {

    @Id
    private UUID id;

    @Column(name = "passenger_id", nullable = false, updatable = false)
    private Long passengerId;

    @Column(name = "flight_id", nullable = false, updatable = false)
    private Long flightId;

    @Column(name = "created_by_user_id", nullable = false, length = 64, updatable = false)
    private String createdByUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ReservationStatus status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Reservation() {
        // requerido por JPA
    }

    public static Reservation create(Long passengerId, Long flightId, String createdByUserId, Instant now) {
        Objects.requireNonNull(passengerId, "passengerId");
        Objects.requireNonNull(flightId, "flightId");
        Objects.requireNonNull(createdByUserId, "createdByUserId");

        Reservation reservation = new Reservation();
        reservation.id = UUID.randomUUID();
        reservation.passengerId = passengerId;
        reservation.flightId = flightId;
        reservation.createdByUserId = createdByUserId;
        reservation.status = ReservationStatus.PENDING;
        reservation.createdAt = now;
        reservation.updatedAt = now;
        return reservation;
    }

    /** Flight Service confirmó la plaza. */
    public void confirm(Instant now) {
        transitionTo(ReservationStatus.CONFIRMED, now,
                "Solo se puede confirmar una reserva pendiente");
    }

    /** No se pudo ocupar plaza (sin disponibilidad, vuelo no reservable, vuelo inexistente o fallo de integración). */
    public void reject(Instant now) {
        transitionTo(ReservationStatus.REJECTED, now,
                "Solo se puede rechazar una reserva pendiente");
    }

    /** RN-07: solo una reserva confirmada se puede cancelar; cancelar de nuevo no es válido. */
    public void cancel(Instant now) {
        transitionTo(ReservationStatus.CANCELLED, now,
                "Solo se puede cancelar una reserva confirmada");
    }

    public boolean isOwnedBy(String userId) {
        return createdByUserId.equals(userId);
    }

    private void transitionTo(ReservationStatus target, Instant now, String message) {
        if (!status.canTransitionTo(target)) {
            throw new BusinessRuleException(ErrorCode.INVALID_RESERVATION_STATE,
                    message + " (estado actual: " + status + ")");
        }
        this.status = target;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public Long getPassengerId() { return passengerId; }
    public Long getFlightId() { return flightId; }
    public String getCreatedByUserId() { return createdByUserId; }
    public ReservationStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
