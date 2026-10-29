package ar.edu.utn.frc.aerogest.reservation;

import ar.edu.utn.frc.aerogest.reservation.client.FlightGateway;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base de las pruebas de integración: aplicación completa (seguridad incluida) sobre un
 * PostgreSQL real levantado por Testcontainers. Flight Service se reemplaza por un doble del
 * puerto {@link FlightGateway}: acá se prueba Reservation Service; la integración HTTP real
 * con Flight está cubierta por HttpFlightGatewayTest y el smoke de los dos servicios.
 *
 * Los efectos sobre los datos se verifican con JDBC directo, sin pasar por JPA.
 */
@SpringBootTest(properties = {
        // La base real la aporta Testcontainers; estos valores solo evitan placeholders sin resolver
        "spring.datasource.url=jdbc:postgresql://localhost:5432/ignored",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/aerogest",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/aerogest/protocol/openid-connect/certs",
        "aerogest.keycloak.token-uri=http://localhost:8180/realms/aerogest/protocol/openid-connect/token",
        "aerogest.keycloak.client-id=reservation-service",
        "aerogest.keycloak.client-secret=test",
        // El job se prueba invocándolo directamente, no por el reloj
        "aerogest.reconciliation.enabled=false"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AbstractIntegrationTest {

    static {
        // PostgreSQL no reconoce la zona "America/Buenos_Aires" que usa la JVM en algunos Windows
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @MockitoBean
    protected FlightGateway flightGateway;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from reservations");
        jdbc.update("delete from passengers");
    }

    // ---------- lecturas directas de la base ----------

    protected String reservationStatus(UUID id) {
        return jdbc.queryForObject("select status from reservations where id = ?", String.class, id);
    }

    protected int reservationCount() {
        return jdbc.queryForObject("select count(*) from reservations", Integer.class);
    }

    protected int reservationCount(String status) {
        return jdbc.queryForObject("select count(*) from reservations where status = ?", Integer.class, status);
    }

    protected int passengerCount() {
        return jdbc.queryForObject("select count(*) from passengers", Integer.class);
    }
}
