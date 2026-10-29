package ar.edu.utn.frc.aerogest.reservation.dto;

import ar.edu.utn.frc.aerogest.reservation.domain.DocumentType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Datos para registrar un pasajero. El formato del documento según su tipo lo valida el dominio. */
public record CreatePassengerRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 100, message = "El nombre no puede superar los 100 caracteres")
        String firstName,

        @NotBlank(message = "El apellido es obligatorio")
        @Size(max = 100, message = "El apellido no puede superar los 100 caracteres")
        String lastName,

        @NotNull(message = "El tipo de documento es obligatorio (DNI o PASSPORT)")
        DocumentType documentType,

        @NotBlank(message = "El número de documento es obligatorio")
        @Size(max = 30, message = "El número de documento no puede superar los 30 caracteres")
        String documentNumber
) {
}
