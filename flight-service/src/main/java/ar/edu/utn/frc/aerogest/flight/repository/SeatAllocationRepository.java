package ar.edu.utn.frc.aerogest.flight.repository;

import ar.edu.utn.frc.aerogest.flight.domain.AllocationStatus;
import ar.edu.utn.frc.aerogest.flight.domain.SeatAllocation;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SeatAllocationRepository extends JpaRepository<SeatAllocation, UUID> {

    /**
     * Pasa la asignación de ALLOCATED a RELEASED en una sola sentencia atómica.
     * Si dos liberaciones de la misma reserva llegan a la vez, solo una afecta la fila
     * (la otra encuentra el estado ya cambiado), así la plaza se devuelve una única vez (RN-07).
     *
     * @return 1 si esta llamada fue la que liberó; 0 si ya estaba liberada o no existe.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update SeatAllocation a
               set a.status = :released, a.releasedAt = :now
             where a.reservationId = :id
               and a.status = :allocated
            """)
    int markReleased(@Param("id") UUID id,
                     @Param("allocated") AllocationStatus allocated,
                     @Param("released") AllocationStatus released,
                     @Param("now") Instant now);
}
