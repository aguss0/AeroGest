package ar.edu.utn.frc.aerogest.reservation.service;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Job de reconciliación (ADR 0001, decisión 6). Cada {@code interval} resuelve las reservas que
 * siguen PENDING después de {@code pending-timeout}: libera su plaza en Flight y las rechaza.
 * Se puede desactivar con aerogest.reconciliation.enabled=false (por ejemplo, en pruebas).
 */
@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "aerogest.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PendingReservationReconciler {

    private static final Logger log = LoggerFactory.getLogger(PendingReservationReconciler.class);

    private final ReservationService reservationService;
    private final Duration pendingTimeout;
    private final int batchSize;

    public PendingReservationReconciler(ReservationService reservationService,
                                        @Value("${aerogest.reconciliation.pending-timeout:PT5M}") Duration pendingTimeout,
                                        @Value("${aerogest.reconciliation.batch-size:50}") int batchSize) {
        this.reservationService = reservationService;
        this.pendingTimeout = pendingTimeout;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${aerogest.reconciliation.interval:PT1M}",
            initialDelayString = "${aerogest.reconciliation.interval:PT1M}")
    public void run() {
        int resolved = reservationService.resolveStalePending(pendingTimeout, batchSize);
        if (resolved > 0) {
            log.info("Reconciliación: {} reservas PENDING resueltas como REJECTED", resolved);
        }
    }
}
