package ar.edu.utn.frc.aerogest.flight.repository;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FlightRepository extends JpaRepository<Flight, Long>, JpaSpecificationExecutor<Flight> {

    boolean existsByCode(String code);

    /**
     * Ocupa una plaza en UNA sola sentencia atómica (RN-04 y RN-05).
     * La condición "seatsTaken < capacity" se evalúa dentro de la base de datos: si dos
     * solicitudes compiten por la última plaza, PostgreSQL serializa el UPDATE sobre la fila
     * del vuelo y la segunda ve el valor ya actualizado, por lo que no actualiza ninguna fila.
     *
     * @return 1 si se ocupó la plaza; 0 si el vuelo no existe, no es reservable o no tiene plazas.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Flight f
               set f.seatsTaken = f.seatsTaken + 1, f.updatedAt = :now
             where f.id = :id
               and f.status = :status
               and f.departureAt > :now
               and f.seatsTaken < f.capacity
            """)
    int allocateSeat(@Param("id") Long id, @Param("status") FlightStatus status, @Param("now") Instant now);

    /** Libera una plaza de forma atómica. Devuelve 1 si se liberó; 0 si el contador ya estaba en 0 o no existe el vuelo. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Flight f
               set f.seatsTaken = f.seatsTaken - 1, f.updatedAt = :now
             where f.id = :id
               and f.seatsTaken > 0
            """)
    int releaseSeat(@Param("id") Long id, @Param("now") Instant now);
}
