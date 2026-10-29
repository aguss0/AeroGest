package ar.edu.utn.frc.aerogest.flight.controller;

import ar.edu.utn.frc.aerogest.flight.domain.FlightStatus;
import ar.edu.utn.frc.aerogest.flight.dto.ChangeStatusRequest;
import ar.edu.utn.frc.aerogest.flight.dto.CreateFlightRequest;
import ar.edu.utn.frc.aerogest.flight.dto.FlightResponse;
import ar.edu.utn.frc.aerogest.flight.dto.FlightSearchCriteria;
import ar.edu.utn.frc.aerogest.flight.dto.PageResponse;
import ar.edu.utn.frc.aerogest.flight.dto.UpdateFlightRequest;
import ar.edu.utn.frc.aerogest.flight.service.FlightService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.time.Instant;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API pública de vuelos. El controlador solo traduce HTTP: valida el formato (@Valid) y delega
 * en el servicio. Los permisos (ADMIN para escribir, USER o ADMIN para leer) se definen en SecurityConfig.
 */
@RestController
@RequestMapping("/api/v1/flights")
@Tag(name = "Vuelos", description = "Administración y consulta de vuelos")
public class FlightController {

    private final FlightService flightService;

    public FlightController(FlightService flightService) {
        this.flightService = flightService;
    }

    @PostMapping
    @Operation(summary = "Crear un vuelo (ADMIN)")
    public ResponseEntity<FlightResponse> create(@Valid @RequestBody CreateFlightRequest request) {
        FlightResponse created = flightService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/flights/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Listar vuelos con filtros opcionales (USER, ADMIN)")
    public PageResponse<FlightResponse> search(
            @RequestParam(required = false) String origin,
            @RequestParam(required = false) String destination,
            @RequestParam(required = false) FlightStatus status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant departureFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant departureTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        FlightSearchCriteria criteria = new FlightSearchCriteria(origin, destination, status, departureFrom, departureTo);
        return flightService.search(criteria, page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un vuelo (USER, ADMIN)")
    public FlightResponse getById(@PathVariable Long id) {
        return flightService.getById(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Modificar un vuelo (ADMIN)")
    public FlightResponse update(@PathVariable Long id, @Valid @RequestBody UpdateFlightRequest request) {
        return flightService.update(id, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Cambiar el estado de un vuelo (ADMIN)")
    public FlightResponse changeStatus(@PathVariable Long id, @Valid @RequestBody ChangeStatusRequest request) {
        return flightService.changeStatus(id, request);
    }
}
