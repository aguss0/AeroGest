package ar.edu.utn.frc.aerogest.reservation.domain;

import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;

/**
 * Pasajero: datos mínimos para identificarlo. El usuario (claim "sub" de Keycloak) que lo registró
 * es su dueño; esa es la base del control de acceso (RN-09). El documento identifica a una única
 * persona en todo el sistema (RN-14) y no se puede cambiar.
 */
@Entity
@Table(name = "passengers")
public class Passenger {

    private static final int MAX_NAME_LENGTH = 100;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_user_id", nullable = false, length = 64, updatable = false)
    private String ownerUserId;

    @Column(name = "first_name", nullable = false, length = MAX_NAME_LENGTH)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = MAX_NAME_LENGTH)
    private String lastName;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 10, updatable = false)
    private DocumentType documentType;

    @Column(name = "document_number", nullable = false, length = 30, updatable = false)
    private String documentNumber;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Passenger() {
        // requerido por JPA
    }

    /** Registra un pasajero validando sus datos y normalizando el número de documento. */
    public static Passenger register(String ownerUserId, String firstName, String lastName,
                                     DocumentType documentType, String documentNumber, Instant now) {
        Objects.requireNonNull(ownerUserId, "ownerUserId");
        Objects.requireNonNull(documentType, "documentType");

        String normalizedNumber = documentType.normalize(documentNumber);
        if (!documentType.isValid(normalizedNumber)) {
            throw new BusinessRuleException(ErrorCode.INVALID_PASSENGER_DATA,
                    "El número de documento no es válido para el tipo " + documentType);
        }

        Passenger passenger = new Passenger();
        passenger.ownerUserId = ownerUserId;
        passenger.firstName = cleanName(firstName, "El nombre");
        passenger.lastName = cleanName(lastName, "El apellido");
        passenger.documentType = documentType;
        passenger.documentNumber = normalizedNumber;
        passenger.createdAt = now;
        passenger.updatedAt = now;
        return passenger;
    }

    /** Corrige nombre y apellido. El documento no se puede modificar. */
    public void rename(String firstName, String lastName, Instant now) {
        this.firstName = cleanName(firstName, "El nombre");
        this.lastName = cleanName(lastName, "El apellido");
        this.updatedAt = now;
    }

    public boolean isOwnedBy(String userId) {
        return ownerUserId.equals(userId);
    }

    private static String cleanName(String value, String label) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            throw new BusinessRuleException(ErrorCode.INVALID_PASSENGER_DATA, label + " es obligatorio");
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            throw new BusinessRuleException(ErrorCode.INVALID_PASSENGER_DATA,
                    label + " no puede superar los " + MAX_NAME_LENGTH + " caracteres");
        }
        return cleaned;
    }

    public Long getId() { return id; }
    public String getOwnerUserId() { return ownerUserId; }
    public String getFirstName() { return firstName; }
    public String getLastName() { return lastName; }
    public DocumentType getDocumentType() { return documentType; }
    public String getDocumentNumber() { return documentNumber; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
