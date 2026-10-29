package ar.edu.utn.frc.aerogest.reservation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.utn.frc.aerogest.reservation.exception.BusinessRuleException;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Datos del pasajero y normalización del documento (base de RN-14). */
class PassengerTest {

    private static final Instant NOW = Instant.parse("2026-11-01T10:00:00Z");

    private void assertInvalidData(Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(BusinessRuleException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PASSENGER_DATA));
    }

    @Test
    void registraUnPasajeroValido() {
        Passenger passenger = Passenger.register("user-1", "  Ana ", " Pérez ", DocumentType.DNI, "30123456", NOW);

        assertThat(passenger.getFirstName()).isEqualTo("Ana");
        assertThat(passenger.getLastName()).isEqualTo("Pérez");
        assertThat(passenger.getDocumentNumber()).isEqualTo("30123456");
        assertThat(passenger.isOwnedBy("user-1")).isTrue();
        assertThat(passenger.isOwnedBy("user-2")).isFalse();
    }

    @Test
    void normalizaElDocumentoParaQueLaUnicidadSeaEfectiva_RN14() {
        Passenger withDots = Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "30.123.456", NOW);
        Passenger plain = Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, " 30123456 ", NOW);

        assertThat(withDots.getDocumentNumber()).isEqualTo(plain.getDocumentNumber()).isEqualTo("30123456");
    }

    @Test
    void normalizaElPasaporteAMayusculasSinSeparadores() {
        Passenger passenger = Passenger.register("u", "Ana", "Pérez", DocumentType.PASSPORT, "aa-123 456", NOW);

        assertThat(passenger.getDocumentNumber()).isEqualTo("AA123456");
    }

    @Test
    void rechazaUnDniConLetrasOLongitudIncorrecta() {
        assertInvalidData(() -> Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "ABC12345", NOW));
        assertInvalidData(() -> Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "123456", NOW));
        assertInvalidData(() -> Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "123456789", NOW));
        assertInvalidData(() -> Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "", NOW));
        assertInvalidData(() -> Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, null, NOW));
    }

    @Test
    void rechazaNombreOApellidoVacioOMuyLargo() {
        assertInvalidData(() -> Passenger.register("u", "   ", "Pérez", DocumentType.DNI, "30123456", NOW));
        assertInvalidData(() -> Passenger.register("u", "Ana", null, DocumentType.DNI, "30123456", NOW));
        assertInvalidData(() -> Passenger.register("u", "x".repeat(101), "Pérez", DocumentType.DNI, "30123456", NOW));
    }

    @Test
    void permiteCorregirElNombreSinTocarElDocumento() {
        Passenger passenger = Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "30123456", NOW);

        passenger.rename("María", "Gómez", NOW.plusSeconds(60));

        assertThat(passenger.getFirstName()).isEqualTo("María");
        assertThat(passenger.getLastName()).isEqualTo("Gómez");
        assertThat(passenger.getDocumentNumber()).isEqualTo("30123456");
        assertThat(passenger.getUpdatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    void elRenombradoTambienValidaLosDatos() {
        Passenger passenger = Passenger.register("u", "Ana", "Pérez", DocumentType.DNI, "30123456", NOW);

        assertInvalidData(() -> passenger.rename(" ", "Gómez", NOW));
    }
}
