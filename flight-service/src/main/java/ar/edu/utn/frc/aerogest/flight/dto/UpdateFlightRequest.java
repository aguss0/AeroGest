package ar.edu.utn.frc.aerogest.flight.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
        Integer capacity,

        @Schema(description = "Indicativo de llamada ICAO de la aeronave. Opcional, junto con aircraftIcao24. Si se omiten los dos, el vuelo queda sin aeronave", example = "ARG1132")
        @Pattern(regexp = "^[A-Za-z0-9]{3,8}$", message = "El callsign debe tener de 3 a 8 letras o números")
        String callsign,

        @Schema(description = "Dirección del transpondedor de la aeronave, 6 dígitos hexadecimales. Opcional, junto con callsign", example = "e0659a")
        @Pattern(regexp = "^[0-9A-Fa-f]{6}$", message = "El icao24 debe tener 6 dígitos hexadecimales")
        String aircraftIcao24
) {

    /** Modificación sin aeronave asignada. */
    public UpdateFlightRequest(String origin, String destination, Instant departureAt,
                               Instant arrivalAt, Integer capacity) {
        this(origin, destination, departureAt, arrivalAt, capacity, null, null);
    }
}
