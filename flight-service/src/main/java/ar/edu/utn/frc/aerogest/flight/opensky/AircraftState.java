package ar.edu.utn.frc.aerogest.flight.opensky;

import java.time.Instant;

/**
 * Lo que AeroGest usa de un "state vector" de OpenSky.
 *
 * @param icao24      dirección del transpondedor, en minúsculas
 * @param callsign    indicativo de llamada sin espacios (puede faltar: null)
 * @param onGround    true si la aeronave informa estar en tierra
 * @param lastContact último mensaje recibido de la aeronave (puede faltar: null)
 */
public record AircraftState(String icao24, String callsign, boolean onGround, Instant lastContact) {
}
