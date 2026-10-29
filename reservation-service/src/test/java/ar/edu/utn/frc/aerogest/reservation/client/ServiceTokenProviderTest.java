package ar.edu.utn.frc.aerogest.reservation.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.edu.utn.frc.aerogest.reservation.config.FlightServiceProperties;
import ar.edu.utn.frc.aerogest.reservation.config.KeycloakClientProperties;
import ar.edu.utn.frc.aerogest.reservation.exception.ErrorCode;
import ar.edu.utn.frc.aerogest.reservation.exception.FlightServiceException;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ServiceTokenProviderTest {

    /** Reloj que se puede adelantar a mano. */
    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2030-01-01T10:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return now;
        }
    }

    private FakeHttpServer keycloak;
    private MutableClock clock;
    private ServiceTokenProvider provider;
    private final AtomicInteger counter = new AtomicInteger();

    @BeforeEach
    void setUp() throws Exception {
        keycloak = new FakeHttpServer();
        clock = new MutableClock();
        keycloak.respondWith(r -> FakeHttpServer.Reply.of(200,
                "{\"access_token\":\"token-" + counter.incrementAndGet() + "\",\"expires_in\":300}"));
        provider = build(keycloak.baseUrl() + "/realms/aerogest/protocol/openid-connect/token");
    }

    private ServiceTokenProvider build(String tokenUri) {
        return new ServiceTokenProvider(
                HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build(),
                new KeycloakClientProperties(tokenUri, "reservation-service", "se cret&1"),
                new FlightServiceProperties("http://unused", 500, 1000), clock);
    }

    @AfterEach
    void tearDown() {
        keycloak.close();
    }

    @Test
    void pideElTokenConClientCredentialsYCodificaElSecreto() {
        assertThat(provider.getToken()).isEqualTo("token-1");

        FakeHttpServer.Received request = keycloak.received().get(0);
        assertThat(request.method()).isEqualTo("POST");
        assertThat(request.path()).isEqualTo("/realms/aerogest/protocol/openid-connect/token");
        assertThat(request.body())
                .contains("grant_type=client_credentials")
                .contains("client_id=reservation-service")
                .contains("client_secret=se+cret%261");
    }

    @Test
    void reutilizaElTokenMientrasNoVenza() {
        provider.getToken();
        clock.advance(Duration.ofSeconds(200));

        assertThat(provider.getToken()).isEqualTo("token-1");
        assertThat(keycloak.received()).hasSize(1);
    }

    @Test
    void renuevaElTokenAntesDeQueVenza() {
        provider.getToken();
        clock.advance(Duration.ofSeconds(300).minus(ServiceTokenProvider.SKEW));

        assertThat(provider.getToken()).isEqualTo("token-2");
        assertThat(keycloak.received()).hasSize(2);
    }

    @Test
    void invalidateForzaUnTokenNuevo() {
        provider.getToken();
        provider.invalidate();

        assertThat(provider.getToken()).isEqualTo("token-2");
    }

    @Test
    void keycloakRechazaLasCredenciales() {
        keycloak.respondWith(FakeHttpServer.Reply.of(401, "{\"error\":\"unauthorized_client\"}"));

        assertThatThrownBy(provider::getToken)
                .isInstanceOfSatisfying(FlightServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE));
    }

    @Test
    void respuestaSinAccessTokenEsInvalida() {
        keycloak.respondWith(FakeHttpServer.Reply.of(200, "{\"expires_in\":300}"));

        assertThatThrownBy(provider::getToken).isInstanceOf(FlightServiceException.class);
    }

    @Test
    void respuestaQueNoEsJsonEsInvalida() {
        keycloak.respondWith(FakeHttpServer.Reply.of(200, "<html>nope</html>"));

        assertThatThrownBy(provider::getToken).isInstanceOf(FlightServiceException.class);
    }

    @Test
    void keycloakCaidoSeInformaComoNoDisponible() {
        keycloak.close();

        assertThatThrownBy(provider::getToken)
                .isInstanceOfSatisfying(FlightServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FLIGHT_SERVICE_UNAVAILABLE));
    }
}
