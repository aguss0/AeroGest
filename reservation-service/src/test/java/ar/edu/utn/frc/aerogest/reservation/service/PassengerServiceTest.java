package ar.edu.utn.frc.aerogest.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ar.edu.utn.frc.aerogest.reservation.domain.DocumentType;
import ar.edu.utn.frc.aerogest.reservation.domain.Passenger;
import ar.edu.utn.frc.aerogest.reservation.dto.CreatePassengerRequest;
import ar.edu.utn.frc.aerogest.reservation.dto.PassengerResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.UpdatePassengerRequest;
import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.repository.PassengerRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

/** Casos de uso de pasajeros con el repositorio simulado (RN-09, RN-14). */
class PassengerServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");
    private static final CurrentUser OWNER = CurrentUser.user("user-1");
    private static final CurrentUser OTHER = CurrentUser.user("user-2");
    private static final CurrentUser ADMIN = CurrentUser.admin("admin-1");

    private final PassengerRepository passengers = mock(PassengerRepository.class);
    private PassengerService service;

    @BeforeEach
    void setUp() {
        service = new PassengerService(passengers, Clock.fixed(NOW, ZoneOffset.UTC));
        when(passengers.saveAndFlush(any(Passenger.class))).thenAnswer(inv -> {
            Passenger saved = inv.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 7L);
            return saved;
        });
    }

    private Passenger existing() {
        Passenger passenger = Passenger.register("user-1", "Ana", "Pérez", DocumentType.DNI, "30123456", NOW);
        ReflectionTestUtils.setField(passenger, "id", 7L);
        when(passengers.findById(7L)).thenReturn(Optional.of(passenger));
        return passenger;
    }

    private void assertRule(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    // ---------- alta ----------

    @Test
    void registraUnPasajeroANombreDelUsuario() {
        PassengerResponse created = service.register(OWNER,
                new CreatePassengerRequest("Ana", "Pérez", DocumentType.DNI, "30.123.456"));

        assertThat(created.id()).isEqualTo(7L);
        assertThat(created.documentNumber()).isEqualTo("30123456");
        assertThat(created.ownerUserId()).isEqualTo("user-1");
    }

    @Test
    void rechazaUnDocumentoYaRegistrado_RN14() {
        when(passengers.existsByDocumentTypeAndDocumentNumber(DocumentType.DNI, "30123456")).thenReturn(true);

        // Se compara con el número normalizado: "30.123.456" y "30123456" son el mismo documento
        assertRule(() -> service.register(OWNER,
                new CreatePassengerRequest("Ana", "Pérez", DocumentType.DNI, "30.123.456")),
                ErrorCode.DUPLICATE_PASSENGER_DOCUMENT);
        verify(passengers, never()).saveAndFlush(any());
    }

    @Test
    void traduceLaRestriccionUnicaDeLaBaseAlMismoError_RN14() {
        // Dos altas simultáneas: las dos pasan la verificación previa y la base frena a una
        when(passengers.saveAndFlush(any(Passenger.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint \"uq_passengers_document\""));

        assertRule(() -> service.register(OWNER,
                new CreatePassengerRequest("Ana", "Pérez", DocumentType.DNI, "30123456")),
                ErrorCode.DUPLICATE_PASSENGER_DOCUMENT);
    }

    @Test
    void otraViolacionDeIntegridadNoSeDisfrazaDeDuplicado() {
        when(passengers.saveAndFlush(any(Passenger.class)))
                .thenThrow(new DataIntegrityViolationException("violates check constraint \"ck_passengers_document_type\""));

        assertThatThrownBy(() -> service.register(OWNER,
                new CreatePassengerRequest("Ana", "Pérez", DocumentType.DNI, "30123456")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void validaElFormatoDelDocumentoAntesDeConsultarLaBase() {
        assertRule(() -> service.register(OWNER,
                new CreatePassengerRequest("Ana", "Pérez", DocumentType.DNI, "ABC")),
                ErrorCode.INVALID_PASSENGER_DATA);
        verify(passengers, never()).existsByDocumentTypeAndDocumentNumber(any(), any());
    }

    // ---------- acceso (RN-09) ----------

    @Test
    void elDuenoPuedeConsultarYCorregirSuPasajero() {
        existing();

        assertThat(service.getById(OWNER, 7L).firstName()).isEqualTo("Ana");
        assertThat(service.rename(OWNER, 7L, new UpdatePassengerRequest("María", "Gómez")).firstName())
                .isEqualTo("María");
    }

    @Test
    void unUsuarioNoPuedeVerNiModificarPasajerosAjenos() {
        existing();

        assertRule(() -> service.getById(OTHER, 7L), ErrorCode.ACCESS_DENIED);
        assertRule(() -> service.rename(OTHER, 7L, new UpdatePassengerRequest("X", "Y")), ErrorCode.ACCESS_DENIED);
    }

    @Test
    void unAdminPuedeVerPasajerosDeCualquierUsuario() {
        existing();

        assertThat(service.getById(ADMIN, 7L).ownerUserId()).isEqualTo("user-1");
    }

    @Test
    void pasajeroInexistente() {
        when(passengers.findById(99L)).thenReturn(Optional.empty());

        assertRule(() -> service.getById(ADMIN, 99L), ErrorCode.PASSENGER_NOT_FOUND);
    }

    @Test
    void unUsuarioListaSoloSusPasajerosYUnAdminTodos() {
        Page<Passenger> empty = new PageImpl<>(List.of());
        when(passengers.findByOwnerUserId(eq("user-1"), any(Pageable.class))).thenReturn(empty);
        when(passengers.findAll(any(Pageable.class))).thenReturn(empty);

        service.list(OWNER, 0, 20);
        verify(passengers).findByOwnerUserId(eq("user-1"), any(Pageable.class));
        verify(passengers, never()).findAll(any(Pageable.class));

        service.list(ADMIN, 0, 20);
        verify(passengers).findAll(any(Pageable.class));
    }
}
