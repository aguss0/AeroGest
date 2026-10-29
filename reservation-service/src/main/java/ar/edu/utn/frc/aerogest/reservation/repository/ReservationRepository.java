package ar.edu.utn.frc.aerogest.reservation.repository;

import ar.edu.utn.frc.aerogest.reservation.domain.Reservation;
import ar.edu.utn.frc.aerogest.reservation.domain.ReservationStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

    /**
     * Verificación previa de RN-06 para dar un error claro. La garantía real es el índice único
     * parcial de la base (uq_reservations_active_passenger_flight), que también cubre solicitudes simultáneas.
     */
    boolean existsByPassengerIdAndFlightIdAndStatusIn(Long passengerId, Long flightId,
                                                      Collection<ReservationStatus> statuses);

    /** Reservas de un usuario (un USER solo ve las suyas, RN-09). */
    Page<Reservation> findByCreatedByUserId(String createdByUserId, Pageable pageable);

    /** Para el job que resuelve reservas que quedaron PENDING por un fallo de integración. */
    List<Reservation> findByStatusAndCreatedAtBefore(ReservationStatus status, Instant cutoff, Pageable pageable);
}
