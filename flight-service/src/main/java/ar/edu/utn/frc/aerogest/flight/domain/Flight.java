package ar.edu.utn.frc.aerogest.flight.domain;

import ar.edu.utn.frc.aerogest.flight.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.flight.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;

/**
 * Vuelo comercial. Las reglas que dependen solo de sus propios datos viven acá
 * (horarios, ruta, capacidad, estados), no en el controlador.
 *
 * La entidad es el dominio y NO se expone en la API: se usan DTOs (Parte 2).
 */
@Entity
@Table(name = "flights")
public class Flight {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // El código no cambia después de crear el vuelo (RN-01)
    @Column(nullable = false, length = 30, updatable = false)
    private String code;

    @Column(nullable = false, length = 3)
    private String origin;

    @Column(nullable = false, length = 3)
    private String destination;

    @Column(name = "departure_at", nullable = false)
    private Instant departureAt;

    @Column(name = "arrival_at", nullable = false)
    private Instant arrivalAt;

    @Column(nullable = false)
    private int capacity;

    /**
     * Plazas ocupadas. updatable = false: JAMÁS se escribe desde la entidad, solo con las
     * sentencias atómicas del repositorio (Parte 3). Así, una edición del vuelo con datos
     * viejos no puede pisar el contador.
     */
    @Column(name = "seats_taken", nullable = false, updatable = false)
    private int seatsTaken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private FlightStatus status;

    @Version
    private Long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Flight() {
        // requerido por JPA
    }

    /** Crea un vuelo nuevo en estado SCHEDULED validando RN-02, RN-03, RN-11, RN-12. */
    public static Flight create(String code, String origin, String destination,
                                Instant departureAt, Instant arrivalAt, int capacity, Instant now) {
        Objects.requireNonNull(code, "code");
        validateData(origin, destination, departureAt, arrivalAt, capacity, now);

        Flight flight = new Flight();
        flight.code = code;
        flight.origin = origin;
        flight.destination = destination;
        flight.departureAt = departureAt;
        flight.arrivalAt = arrivalAt;
        flight.capacity = capacity;
        flight.seatsTaken = 0;
        flight.status = FlightStatus.SCHEDULED;
        flight.createdAt = now;
        flight.updatedAt = now;
        return flight;
    }

    /** Modifica los datos del vuelo. No permite tocar vuelos en estado terminal. */
    public void update(String origin, String destination, Instant departureAt,
                       Instant arrivalAt, int capacity, Instant now) {
        if (status.isTerminal()) {
            throw new BusinessRuleException(ErrorCode.FLIGHT_NOT_MODIFIABLE,
                    "No se puede modificar un vuelo en estado " + status);
        }
        validateData(origin, destination, departureAt, arrivalAt, capacity, now);
        if (capacity < seatsTaken) {
            throw new BusinessRuleException(ErrorCode.INVALID_CAPACITY,
                    "La capacidad (" + capacity + ") no puede ser menor a las plazas ya ocupadas (" + seatsTaken + ")");
        }
        this.origin = origin;
        this.destination = destination;
        this.departureAt = departureAt;
        this.arrivalAt = arrivalAt;
        this.capacity = capacity;
        this.updatedAt = now;
    }

    /** Cambia el estado respetando las transiciones válidas (RN-08). */
    public void changeStatus(FlightStatus target, Instant now) {
        Objects.requireNonNull(target, "target");
        if (!status.canTransitionTo(target)) {
            throw new BusinessRuleException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "No se puede pasar de " + status + " a " + target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    /** RN-04: el vuelo admite reservas nuevas solo si está programado y todavía no salió. */
    public boolean isReservable(Instant now) {
        return status == FlightStatus.SCHEDULED && departureAt.isAfter(now);
    }

    public int availableSeats() {
        return capacity - seatsTaken;
    }

    private static void validateData(String origin, String destination, Instant departureAt,
                                     Instant arrivalAt, int capacity, Instant now) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departureAt, "departureAt");
        Objects.requireNonNull(arrivalAt, "arrivalAt");

        if (origin.equals(destination)) {
            throw new BusinessRuleException(ErrorCode.INVALID_ROUTE,
                    "El origen y el destino no pueden ser el mismo");
        }
        if (!arrivalAt.isAfter(departureAt)) {
            throw new BusinessRuleException(ErrorCode.INVALID_SCHEDULE,
                    "La llegada debe ser posterior a la salida");
        }
        if (!departureAt.isAfter(now)) {
            throw new BusinessRuleException(ErrorCode.DEPARTURE_IN_PAST,
                    "La salida debe ser una fecha futura");
        }
        if (capacity <= 0) {
            throw new BusinessRuleException(ErrorCode.INVALID_CAPACITY,
                    "La capacidad debe ser mayor a 0");
        }
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getOrigin() { return origin; }
    public String getDestination() { return destination; }
    public Instant getDepartureAt() { return departureAt; }
    public Instant getArrivalAt() { return arrivalAt; }
    public int getCapacity() { return capacity; }
    public int getSeatsTaken() { return seatsTaken; }
    public FlightStatus getStatus() { return status; }
    public Long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
