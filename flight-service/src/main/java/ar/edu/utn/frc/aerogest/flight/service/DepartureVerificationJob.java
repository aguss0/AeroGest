package ar.edu.utn.frc.aerogest.flight.service;

import ar.edu.utn.frc.aerogest.common.web.CorrelationId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de verificación de salida real (RN-16, ADR 0006). Corre fuera del camino de la reserva:
 * si OpenSky está lento o caído, las reservas no se enteran. Cada corrida lleva su propio
 * correlation id en el log ("opensky-..."). Se desactiva con aerogest.opensky.enabled=false.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "aerogest.opensky", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DepartureVerificationJob {

    private static final Logger log = LoggerFactory.getLogger(DepartureVerificationJob.class);

    private final DepartureVerificationService service;

    public DepartureVerificationJob(DepartureVerificationService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${aerogest.opensky.interval:PT5M}",
            initialDelayString = "${aerogest.opensky.initial-delay:PT1M}")
    public void run() {
        MDC.put(CorrelationId.MDC_KEY, "opensky-" + UUID.randomUUID().toString().substring(0, 8));
        try {
            service.verifyUpcoming();
        } catch (RuntimeException e) {
            // Un error inesperado no debe matar el job: se registra y se reintenta en la próxima corrida
            log.error("OpenSky: error inesperado en la verificación de salidas", e);
        } finally {
            MDC.remove(CorrelationId.MDC_KEY);
        }
    }
}
