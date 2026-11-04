package ar.edu.utn.frc.aerogest.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity;

import ar.edu.utn.frc.aerogest.common.security.KeycloakRealmRoleConverter;
import ar.edu.utn.frc.aerogest.common.web.CorrelationId;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.JwtMutator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * El gateway enruta a cada servicio, aplica el Security Filter por rol y ruta, no publica la API
 * interna y responde sus propios errores (401, 403, 503, 504) con el formato común.
 *
 * Los servicios son servidores HTTP falsos; Reservation apunta a un puerto cerrado para simular
 * que está caído. Los tokens se simulan con mockJwt pasando por el KeycloakRealmRoleConverter real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ApiGatewayIntegrationTest {

    private static final FakeService flights = new FakeService("flight-service");
    private static final int closedPort = freePort();

    @DynamicPropertySource
    static void routes(DynamicPropertyRegistry registry) {
        registry.add("FLIGHT_SERVICE_URL", flights::baseUrl);
        registry.add("RESERVATION_SERVICE_URL", () -> "http://127.0.0.1:" + closedPort);
        registry.add("GATEWAY_RESPONSE_TIMEOUT", () -> "1s");
        registry.add("KEYCLOAK_ISSUER_URI", () -> "http://localhost:8180/realms/aerogest");
        registry.add("KEYCLOAK_JWK_SET_URI", () -> "http://127.0.0.1:" + closedPort + "/certs");
    }

    @Autowired
    private ApplicationContext context;

    private WebTestClient client;

    @BeforeEach
    void setUp() {
        client = WebTestClient.bindToApplicationContext(context).apply(springSecurity()).configureClient().build();
        flights.reset();
    }

    @AfterAll
    static void stopServices() {
        flights.close();
    }

    private static JwtMutator token(String... roles) {
        return mockJwt()
                .jwt(jwt -> jwt.subject("user-1").claim("realm_access", Map.of("roles", List.of(roles))))
                .authorities(new KeycloakRealmRoleConverter());
    }

    @Test
    void sinTokenDevuelve401ConElFormatoComunYNoLlegaAlServicio() {
        client.get().uri("/api/v1/flights")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().exists(CorrelationId.HEADER)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UNAUTHENTICATED")
                .jsonPath("$.status").isEqualTo(401)
                .jsonPath("$.correlationId").exists();

        assertThat(flights.received()).isEmpty();
    }

    @Test
    void unUsuarioConsultaVuelosYLaSolicitudLlegaAFlightConElCorrelationId() {
        client.mutateWith(token("USER")).get().uri("/api/v1/flights/7?page=0")
                .header(CorrelationId.HEADER, "demo-123")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(CorrelationId.HEADER, "demo-123")
                .expectBody()
                .jsonPath("$.service").isEqualTo("flight-service")
                .jsonPath("$.path").isEqualTo("/api/v1/flights/7");

        assertThat(flights.received()).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("GET");
            assertThat(request.headers().getFirst(CorrelationId.HEADER)).isEqualTo("demo-123");
        });
    }

    @Test
    void sinCorrelationIdElGatewayGeneraUnoYLoReenvia() {
        String generated = client.mutateWith(token("USER")).get().uri("/api/v1/flights")
                .exchange()
                .expectStatus().isOk()
                .returnResult(String.class).getResponseHeaders().getFirst(CorrelationId.HEADER);

        assertThat(generated).hasSize(36);
        assertThat(flights.received().getFirst().headers().getFirst(CorrelationId.HEADER)).isEqualTo(generated);
    }

    @Test
    void soloElAdminCreaVuelos() {
        client.mutateWith(token("USER")).post().uri("/api/v1/flights")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");
        assertThat(flights.received()).isEmpty();

        client.mutateWith(token("ADMIN")).post().uri("/api/v1/flights")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isOk();
        assertThat(flights.received()).singleElement().extracting(FakeService.Received::method).isEqualTo("POST");
    }

    @Test
    void laApiInternaNoSePublicaNiSiquieraParaElRolService() {
        client.mutateWith(token("SERVICE")).post().uri("/internal/v1/flights/1/seat-allocations")
                .contentType(MediaType.APPLICATION_JSON).bodyValue("{}")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").isEqualTo("ACCESS_DENIED");

        client.post().uri("/internal/v1/flights/1/seat-allocations")
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(flights.received()).isEmpty();
    }

    @Test
    void elRolServiceNoTieneAccesoALaApiPublica() {
        client.mutateWith(token("SERVICE")).get().uri("/api/v1/flights")
                .exchange()
                .expectStatus().isForbidden();
        client.mutateWith(token("SERVICE")).get().uri("/api/v1/reservations")
                .exchange()
                .expectStatus().isForbidden();

        assertThat(flights.received()).isEmpty();
    }

    @Test
    void unaRutaNoPublicadaSeDeniega() {
        client.mutateWith(token("ADMIN")).get().uri("/actuator/env")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void servicioCaido_503ConElFormatoComun() {
        client.mutateWith(token("USER")).get().uri("/api/v1/reservations")
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectHeader().contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("UPSTREAM_UNAVAILABLE")
                .jsonPath("$.correlationId").exists();
    }

    @Test
    void servicioQueNoRespondeATiempo_504() {
        client.mutateWith(token("USER")).get().uri("/api/v1/flights/slow")
                .exchange()
                .expectStatus().isEqualTo(504)
                .expectBody().jsonPath("$.code").isEqualTo("UPSTREAM_TIMEOUT");
    }

    @Test
    void laDocumentacionEsPublicaYSoloExponeElGrupoPublicoDelServicio() {
        client.get().uri("/api-docs/flights")
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.path").isEqualTo("/v3/api-docs/public");

        assertThat(flights.received()).singleElement()
                .satisfies(request -> assertThat(request.headers().containsKey("Authorization")).isFalse());
    }

    @Test
    void elHealthDelGatewayEsPublico() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk();
    }

    private static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
