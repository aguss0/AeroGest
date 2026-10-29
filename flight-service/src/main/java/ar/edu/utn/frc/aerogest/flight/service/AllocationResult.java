package ar.edu.utn.frc.aerogest.flight.service;

import ar.edu.utn.frc.aerogest.flight.dto.AllocationResponse;

/** Resultado de asignar una plaza. created=false significa que era un reintento ya procesado. */
public record AllocationResult(AllocationResponse response, boolean created) {
}
