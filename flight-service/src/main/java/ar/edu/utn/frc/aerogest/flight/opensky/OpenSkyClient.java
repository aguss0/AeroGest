package ar.edu.utn.frc.aerogest.flight.opensky;

import java.util.Collection;
import java.util.List;

/** Puerto hacia OpenSky Network. Las pruebas lo reemplazan por un doble. */
public interface OpenSkyClient {

    /**
     * Estado actual de las aeronaves indicadas, en una sola consulta (1 crédito).
     * Una aeronave que no aparece en la lista no está transmitiendo o está fuera de cobertura.
     *
     * @throws OpenSkyException si OpenSky no responde, responde con error, agotó los créditos
     *                          o devuelve algo que no se puede interpretar
     */
    List<AircraftState> statesOf(Collection<String> icao24s);
}
