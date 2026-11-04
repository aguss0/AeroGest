package ar.edu.utn.frc.aerogest.flight.opensky;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuración de la integración con OpenSky Network (ADR 0006).
 *
 * @param enabled          activa el job periódico (el endpoint manual funciona igual)
 * @param baseUrl          raíz de la API REST
 * @param connectTimeoutMs timeout de conexión
 * @param readTimeoutMs    timeout de la respuesta completa
 * @param interval         cada cuánto corre el job (anónimo: 400 créditos/día; cada 5 min = 288)
 * @param lookBehind       hasta cuánto después de la salida programada se sigue verificando
 * @param lookAhead        desde cuánto antes de la salida programada se empieza a verificar
 * @param batchSize        máximo de vuelos por consulta (una consulta = 1 crédito)
 */
@ConfigurationProperties(prefix = "aerogest.opensky")
public record OpenSkyProperties(Boolean enabled, String baseUrl, Integer connectTimeoutMs, Integer readTimeoutMs,
                                Duration interval, Duration lookBehind, Duration lookAhead, Integer batchSize) {

    public OpenSkyProperties {
        if (enabled == null) {
            enabled = true;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://opensky-network.org/api";
        }
        if (connectTimeoutMs == null) {
            connectTimeoutMs = 3000;
        }
        if (readTimeoutMs == null) {
            readTimeoutMs = 5000;
        }
        if (interval == null) {
            interval = Duration.ofMinutes(5);
        }
        if (lookBehind == null) {
            lookBehind = Duration.ofHours(6);
        }
        if (lookAhead == null) {
            lookAhead = Duration.ofHours(2);
        }
        if (batchSize == null) {
            batchSize = 100;
        }
    }
}
