package ar.edu.utn.frc.aerogest.flight.config;

import ar.edu.utn.frc.aerogest.flight.opensky.HttpOpenSkyClient;
import ar.edu.utn.frc.aerogest.flight.opensky.OpenSkyClient;
import ar.edu.utn.frc.aerogest.flight.opensky.OpenSkyProperties;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Cliente de OpenSky Network (ADR 0006). Las pruebas lo reemplazan por un doble. */
@Configuration
@EnableConfigurationProperties(OpenSkyProperties.class)
public class OpenSkyConfig {

    @Bean
    OpenSkyClient openSkyClient(OpenSkyProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return new HttpOpenSkyClient(httpClient, properties);
    }
}
