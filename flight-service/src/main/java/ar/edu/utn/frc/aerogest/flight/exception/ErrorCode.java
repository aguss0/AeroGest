package ar.edu.utn.frc.aerogest.flight.exception;

/**
 * Códigos de error de negocio. Son parte del contrato de la API: Reservation Service
 * y los clientes los usan para distinguir el motivo de un rechazo.
 * La traducción a códigos HTTP se hace en el manejador central de errores.
 */
public enum ErrorCode {
    FLIGHT_NOT_FOUND,
    DUPLICATE_FLIGHT_CODE,        // RN-01
    INVALID_SCHEDULE,             // RN-02
    INVALID_ROUTE,                // RN-11
    DEPARTURE_IN_PAST,            // RN-12
    INVALID_CAPACITY,             // RN-03 / RN-13
    INVALID_STATUS_TRANSITION,    // RN-08
    FLIGHT_NOT_MODIFIABLE,        // vuelo en estado terminal
    FLIGHT_NOT_RESERVABLE,        // RN-04
    NO_AVAILABILITY,              // RN-05
    ALLOCATION_CONFLICT,          // RN-15: la reserva ya está asociada a otro vuelo o su plaza ya fue liberada
    CONCURRENT_MODIFICATION
}
