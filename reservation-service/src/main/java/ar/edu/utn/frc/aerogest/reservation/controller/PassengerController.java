package ar.edu.utn.frc.aerogest.reservation.controller;

import ar.edu.utn.frc.aerogest.reservation.dto.CreatePassengerRequest;
import ar.edu.utn.frc.aerogest.common.web.PageResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.PassengerResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.UpdatePassengerRequest;
import ar.edu.utn.frc.aerogest.reservation.service.PassengerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de pasajeros. Un USER ve y modifica solo los pasajeros que registró; un ADMIN, todos.
 * El controlador solo traduce HTTP; las reglas están en PassengerService y en el dominio.
 */
@RestController
@RequestMapping("/api/v1/passengers")
@Tag(name = "Pasajeros", description = "Registro y consulta de pasajeros")
public class PassengerController {

    private final PassengerService passengerService;

    public PassengerController(PassengerService passengerService) {
        this.passengerService = passengerService;
    }

    @PostMapping
    @Operation(summary = "Registrar un pasajero (el documento es único en todo el sistema)")
    public ResponseEntity<PassengerResponse> register(Authentication authentication,
                                                      @Valid @RequestBody CreatePassengerRequest request) {
        PassengerResponse created = passengerService.register(AuthenticatedUser.from(authentication), request);
        return ResponseEntity.created(URI.create("/api/v1/passengers/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Listar pasajeros (propios; ADMIN ve todos)")
    public PageResponse<PassengerResponse> list(Authentication authentication,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "20") int size) {
        return passengerService.list(AuthenticatedUser.from(authentication), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar un pasajero")
    public PassengerResponse getById(Authentication authentication, @PathVariable Long id) {
        return passengerService.getById(AuthenticatedUser.from(authentication), id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Corregir nombre y apellido (el documento no se modifica)")
    public PassengerResponse rename(Authentication authentication, @PathVariable Long id,
                                    @Valid @RequestBody UpdatePassengerRequest request) {
        return passengerService.rename(AuthenticatedUser.from(authentication), id, request);
    }
}
