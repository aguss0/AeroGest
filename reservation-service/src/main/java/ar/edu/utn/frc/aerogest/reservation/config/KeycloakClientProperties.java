package ar.edu.utn.frc.aerogest.reservation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Credencial de servicio (client_credentials) con la que Reservation llama a Flight. */
@ConfigurationProperties(prefix = "aerogest.keycloak")
public record KeycloakClientProperties(String tokenUri, String clientId, String clientSecret) {
}
