package ar.edu.utn.frc.aerogest.reservation.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties({KeycloakClientProperties.class, FlightServiceProperties.class})
public class IntegrationConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** Cliente HTTP compartido (JDK): pool de conexiones propio y timeout de conexión configurable. */
    @Bean
    HttpClient integrationHttpClient(FlightServiceProperties properties) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }
}
