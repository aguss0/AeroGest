package ar.edu.utn.frc.aerogest.flight.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Registro de que una reserva ocupó una plaza de un vuelo.
 * La clave primaria es el id de la reserva: asegura que cada reserva ocupe, como máximo,
 * una plaza (RN-15, idempotencia). Permite reintentos seguros desde Reservation Service.
 *
 * Implementa Persistable porque el id lo asigna la aplicación (no la base). Sin esto, Spring Data
 * guardaría con "merge", que ante una fila ya existente la ACTUALIZA en silencio en lugar de fallar:
 * dos solicitudes simultáneas de la misma reserva podrían ocupar dos plazas con un solo registro.
 * Con Persistable se usa "persist": el INSERT falla por clave duplicada y se detecta el reintento.
 */
@Entity
@Table(name = "seat_allocations")
public class SeatAllocation implements Persistable<UUID> {

    @Id
    @Column(name = "reservation_id")
    private UUID reservationId;

    @Column(name = "flight_id", nullable = false, updatable = false)
    private Long flightId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AllocationStatus status;

    @Column(name = "allocated_at", nullable = false)
    private Instant allocatedAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    @Transient
    private boolean newEntity = true;

    protected SeatAllocation() {
        // requerido por JPA
    }

    public static SeatAllocation allocate(UUID reservationId, Long flightId, Instant now) {
        SeatAllocation allocation = new SeatAllocation();
        allocation.reservationId = reservationId;
        allocation.flightId = flightId;
        allocation.status = AllocationStatus.ALLOCATED;
        allocation.allocatedAt = now;
        return allocation;
    }

    /**
     * "Ficha de liberación": se crea cuando llega una liberación para una reserva que nunca
     * llegó a ocupar plaza (por ejemplo, la asignación se perdió por un timeout). Si esa
     * asignación llega tarde, encuentra la ficha y se rechaza, así no queda una plaza ocupada
     * por una reserva que ya fue descartada.
     */
    public static SeatAllocation releasedMarker(UUID reservationId, Long flightId, Instant now) {
        SeatAllocation marker = allocate(reservationId, flightId, now);
        marker.status = AllocationStatus.RELEASED;
        marker.releasedAt = now;
        return marker;
    }

    /**
     * Marca la asignación como liberada en memoria. Devuelve false si ya lo estaba.
     * El servicio no lo usa: libera con una sentencia atómica (markReleased). Se mantiene
     * para construir asignaciones ya liberadas.
     */
    public boolean release(Instant now) {
        if (status == AllocationStatus.RELEASED) {
            return false;
        }
        this.status = AllocationStatus.RELEASED;
        this.releasedAt = now;
        return true;
    }

    public boolean isAllocated() {
        return status == AllocationStatus.ALLOCATED;
    }

    @Override
    public UUID getId() {
        return reservationId;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.newEntity = false;
    }

    public UUID getReservationId() { return reservationId; }
    public Long getFlightId() { return flightId; }
    public AllocationStatus getStatus() { return status; }
    public Instant getAllocatedAt() { return allocatedAt; }
    public Instant getReleasedAt() { return releasedAt; }
}