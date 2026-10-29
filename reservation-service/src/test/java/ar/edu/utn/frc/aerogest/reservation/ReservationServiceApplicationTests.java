package ar.edu.utn.frc.aerogest.reservation;

import java.util.TimeZone;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Verifica que la aplicación arranca y que Hibernate valida las entidades contra el esquema
 * creado por Flyway, sobre un PostgreSQL real de Testcontainers.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        // La base real la aporta Testcontainers; estos valores solo evitan placeholders sin resolver
        "spring.datasource.url=jdbc:postgresql://localhost:5432/ignored",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/aerogest",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/aerogest/protocol/openid-connect/certs",
        "aerogest.keycloak.token-uri=http://localhost:8180/realms/aerogest/protocol/openid-connect/token",
        "aerogest.keycloak.client-id=reservation-service",
        "aerogest.keycloak.client-secret=test"
})
class ReservationServiceApplicationTests {

    static {
        // PostgreSQL no reconoce la zona "America/Buenos_Aires" que usa la JVM en algunos Windows
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Test
    void contextLoads() {
    }
}
