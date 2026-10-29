package ar.edu.utn.frc.aerogest.reservation.exception;

/**
 * Códigos de error de negocio de Reservation Service. Son parte del contrato de la API.
 * La traducción a códigos HTTP se hace en el manejador central de errores.
 */
public enum ErrorCode {
    // Pasajeros
    PASSENGER_NOT_FOUND,
    DUPLICATE_PASSENGER_DOCUMENT,     // RN-14
    INVALID_PASSENGER_DATA,

    // Reservas
    RESERVATION_NOT_FOUND,
    DUPLICATE_ACTIVE_RESERVATION,     // RN-06
    INVALID_RESERVATION_STATE,        // RN-07 / RN-08
    ACCESS_DENIED,                    // RN-09: reserva o pasajero ajeno
    CONCURRENT_MODIFICATION,

    // Respuestas de Flight Service que se propagan al cliente
    FLIGHT_NOT_FOUND,
    FLIGHT_NOT_RESERVABLE,            // RN-04
    NO_AVAILABILITY,                  // RN-05
    ALLOCATION_CONFLICT,

    // Fallos de integración (RN-10): nunca se confirma una reserva sin validar
    FLIGHT_SERVICE_UNAVAILABLE,
    FLIGHT_SERVICE_TIMEOUT,
    INVALID_FLIGHT_RESPONSE
}
