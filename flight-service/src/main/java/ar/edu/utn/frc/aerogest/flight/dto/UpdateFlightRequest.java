package ar.edu.utn.frc.aerogest.flight.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

/** Datos modificables de un vuelo. El código no se puede cambiar. */
public record UpdateFlightRequest(
        @NotNull(message = "El origen es obligatorio")
        @Pattern(regexp = "^[A-Z]{3}$", message = "El origen debe ser un código IATA de 3 letras mayúsculas")
        String origin,

        @NotNull(message = "El destino es obligatorio")
        @Pattern(regexp = "^[A-Z]{3}$", message = "El destino debe ser un código IATA de 3 letras mayúsculas")
        String destination,

        @NotNull(message = "La fecha de salida es obligatoria")
        Instant departureAt,

        @NotNull(message = "La fecha de llegada es obligatoria")
        Instant arrivalAt,

        @NotNull(message = "La capacidad es obligatoria")
        @Positive(message = "La capacidad debe ser mayor a 0")
        Integer capacity
) {
}
