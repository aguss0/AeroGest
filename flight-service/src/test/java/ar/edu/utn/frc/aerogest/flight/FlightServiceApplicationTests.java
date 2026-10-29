package ar.edu.utn.frc.aerogest.flight;

import org.junit.jupiter.api.Test;

/**
 * Verifica que la aplicación completa arranca (seguridad, controladores, OpenAPI y migraciones)
 * y que Hibernate valida las entidades contra el esquema creado por Flyway.
 * Reutiliza el contexto de AbstractIntegrationTest, así no se levanta otro contenedor.
 */
class FlightServiceApplicationTests extends AbstractIntegrationTest {

    @Test
    void contextLoads() {
    }
}
