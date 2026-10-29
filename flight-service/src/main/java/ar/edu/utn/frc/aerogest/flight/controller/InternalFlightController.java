package ar.edu.utn.frc.aerogest.flight.controller;

import ar.edu.utn.frc.aerogest.flight.dto.AllocationRequest;
import ar.edu.utn.frc.aerogest.flight.dto.AllocationResponse;
import ar.edu.utn.frc.aerogest.flight.dto.FlightAvailabilityResponse;
import ar.edu.utn.frc.aerogest.flight.service.AllocationResult;
import ar.edu.utn.frc.aerogest.flight.service.SeatInventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de integración. Solo los puede usar el rol SERVICE (la credencial de
 * Reservation Service en Keycloak). No forman parte de la API para usuarios finales.
 */
@RestController
@RequestMapping("/internal/v1/flights")
@Tag(name = "Integración interna", description = "Uso exclusivo de Reservation Service (rol SERVICE)")
public class InternalFlightController {

    private final SeatInventoryService inventoryService;

    public InternalFlightController(SeatInventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/{flightId}/availability")
    @Operation(summary = "Disponibilidad y estado de un vuelo")
    public FlightAvailabilityResponse availability(@PathVariable Long flightId) {
        return inventoryService.getAvailability(flightId);
    }

    @PostMapping("/{flightId}/seat-allocations")
    @Operation(summary = "Ocupar una plaza para una reserva (idempotente por reservationId)",
            description = "201 si ocupó una plaza nueva; 200 si era un reintento ya procesado")
    public ResponseEntity<AllocationResponse> allocate(@PathVariable Long flightId,
                                                       @Valid @RequestBody AllocationRequest request) {
        AllocationResult result = inventoryService.allocate(flightId, request.reservationId());
        HttpStatus status = result.created() ? HttpStatus.CREATED : HttpStatus.OK;
        return ResponseEntity.status(status).body(result.response());
    }

    @DeleteMapping("/{flightId}/seat-allocations/{reservationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Liberar la plaza de una reserva (idempotente)")
    public void release(@PathVariable Long flightId, @PathVariable UUID reservationId) {
        inventoryService.release(flightId, reservationId);
    }
}
