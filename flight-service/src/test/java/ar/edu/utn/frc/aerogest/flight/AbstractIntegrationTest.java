package ar.edu.utn.frc.aerogest.flight;

import static org.assertj.core.api.Assertions.assertThat;

import ar.edu.utn.frc.aerogest.flight.domain.Flight;
import ar.edu.utn.frc.aerogest.flight.repository.FlightRepository;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base de las pruebas de integración: aplicación completa (seguridad incluida) sobre un
 * PostgreSQL real levantado por Testcontainers. Todas las clases que la extienden comparten
 * el mismo contexto y el mismo contenedor, así que la base se arranca una sola vez.
 *
 * Los efectos sobre los datos se verifican con JDBC directo, sin pasar por JPA.
 */
@SpringBootTest(properties = {
        // La base real la aporta Testcontainers; estos valores solo evitan placeholders sin resolver
        "spring.datasource.url=jdbc:postgresql://localhost:5432/ignored",
        "spring.datasource.username=test",
        "spring.datasource.password=test",
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/aerogest",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://localhost:8180/realms/aerogest/protocol/openid-connect/certs"
})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
abstract class AbstractIntegrationTest {

    static {
        // PostgreSQL no reconoce la zona "America/Buenos_Aires" que usa la JVM en algunos Windows
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    protected static final Instant DEPARTURE = Instant.now().plus(Duration.ofDays(30)).truncatedTo(ChronoUnit.SECONDS);
    protected static final Instant ARRIVAL = DEPARTURE.plus(Duration.ofHours(2));

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected FlightRepository flightRepository;

    @BeforeEach
    void cleanDatabase() {
        jdbc.update("delete from seat_allocations");
        jdbc.update("delete from flights");
    }

    protected Flight saveFlight(String code, int capacity) {
        return saveFlight(code, "COR", "EZE", capacity);
    }

    protected Flight saveFlight(String code, String origin, String destination, int capacity) {
        return flightRepository.saveAndFlush(
                Flight.create(code, origin, destination, DEPARTURE, ARRIVAL, capacity, Instant.now()));
    }

    // ---------- lecturas directas de la base ----------

    protected int flightCount() {
        return jdbc.queryForObject("select count(*) from flights", Integer.class);
    }

    protected int seatsTaken(Long flightId) {
        return jdbc.queryForObject("select seats_taken from flights where id = ?", Integer.class, flightId);
    }

    protected String flightStatus(Long flightId) {
        return jdbc.queryForObject("select status from flights where id = ?", String.class, flightId);
    }

    protected int allocationRows(Long flightId, String status) {
        return jdbc.queryForObject(
                "select count(*) from seat_allocations where flight_id = ? and status = ?",
                Integer.class, flightId, status);
    }

    // ---------- concurrencia ----------

    /** Una solicitud HTTP que devuelve su código de estado. */
    @FunctionalInterface
    protected interface Request {
        int run(int index) throws Exception;
    }

    /**
     * Lanza {@code count} solicitudes a la vez: todos los hilos esperan en una "largada" común
     * y se liberan juntos, para maximizar la competencia real por las mismas filas de la base.
     */
    protected List<Integer> runConcurrently(int count, Request request) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch ready = new CountDownLatch(count);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return request.run(index);
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : futures) {
                statuses.add(future.get(90, TimeUnit.SECONDS));
            }
            return statuses;
        } finally {
            pool.shutdownNow();
        }
    }

    protected static long count(List<Integer> statuses, int status) {
        return statuses.stream().filter(s -> s == status).count();
    }
}
