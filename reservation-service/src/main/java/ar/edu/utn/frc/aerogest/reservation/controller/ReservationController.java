package ar.edu.utn.frc.aerogest.reservation.controller;

import ar.edu.utn.frc.aerogest.reservation.dto.CreateReservationRequest;
import ar.edu.utn.frc.aerogest.reservation.dto.PageResponse;
import ar.edu.utn.frc.aerogest.reservation.dto.ReservationResponse;
import ar.edu.utn.frc.aerogest.reservation.service.ReservationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de reservas. Un USER ve y cancela solo sus reservas (una ajena responde 403); un ADMIN, todas.
 */
@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Reservas", description = "Creación, consulta y cancelación de reservas")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @Operation(summary = "Crear una reserva",
            description = "201 con la reserva CONFIRMED si Flight Service asignó la plaza. "
                    + "404/409 si Flight la negó (vuelo inexistente, no reservable, sin plazas); "
                    + "503/504/502 si Flight no respondió correctamente. En los casos de error la reserva no queda confirmada.")
    public ResponseEntity<ReservationResponse> create(Authentication authentication,
                                                      @Valid @RequestBody CreateReservationRequest request) {
        ReservationResponse created = reservationService.create(AuthenticatedUser.from(authentication), request);
        return ResponseEntity.created(URI.create("/api/v1/reservations/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Listar reservas (propias; ADMIN ve todas)")
    public PageResponse<ReservationResponse> list(Authentication authentication,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "20") int size) {
        return reservationService.list(AuthenticatedUser.from(authentication), page, size);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Consultar una reserva")
    public ReservationResponse getById(Authentication authentication, @PathVariable UUID id) {
        return reservationService.getById(AuthenticatedUser.from(authentication), id);
    }

    @PostMapping("/{id}/cancel")
    @Operation(summary = "Cancelar una reserva confirmada y liberar su plaza")
    public ReservationResponse cancel(Authentication authentication, @PathVariable UUID id) {
        return reservationService.cancel(AuthenticatedUser.from(authentication), id);
    }
}
