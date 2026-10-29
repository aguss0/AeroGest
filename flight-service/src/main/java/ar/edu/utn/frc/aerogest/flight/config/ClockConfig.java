package ar.edu.utn.frc.aerogest.flight.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Reloj inyectable. En producción es UTC; en las pruebas se reemplaza por un reloj fijo,
 * así las reglas que dependen de "ahora" (RN-12, RN-04) son determinísticas.
 */
@Configuration
public class ClockConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
