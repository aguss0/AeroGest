package ar.edu.utn.frc.aerogest.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Dirección y timeouts del cliente hacia Flight Service. */
@ConfigurationProperties(prefix = "aerogest.flight-service")
public record FlightServiceProperties(String baseUrl, Integer connectTimeoutMs, Integer readTimeoutMs) {

    public FlightServiceProperties {
        if (connectTimeoutMs == null) {
            connectTimeoutMs = 2000;
        }
        if (readTimeoutMs == null) {
            readTimeoutMs = 3000;
        }
    }
}
