package ar.edu.utn.frc.aerogest.reservation.repository;

import ar.edu.utn.frc.aerogest.reservation.domain.DocumentType;
import ar.edu.utn.frc.aerogest.reservation.domain.Passenger;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PassengerRepository extends JpaRepository<Passenger, Long> {

    boolean existsByDocumentTypeAndDocumentNumber(DocumentType documentType, String documentNumber);

    Optional<Passenger> findByDocumentTypeAndDocumentNumber(DocumentType documentType, String documentNumber);

    Page<Passenger> findByOwnerUserId(String ownerUserId, Pageable pageable);
}
