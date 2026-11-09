package com.skybook.booking.adapter.in.scheduling;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.skybook.booking.application.port.in.SagaRecoveryUseCase;

/**
 * Driving adapter: resumes sagas that stopped progressing (a crash between steps, a partner outage, a
 * lost event). Safe on every replica: each step is idempotent and guarded by optimistic locking.
 */
@Component
class SagaRecoveryJob {

    private final SagaRecoveryUseCase useCase;
    private final Clock clock;

    SagaRecoveryJob(SagaRecoveryUseCase useCase, Clock clock) {
        this.useCase = useCase;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${skybook.booking.saga-recovery-interval:PT30S}", initialDelayString = "PT20S")
    void run() {
        useCase.recoverStalledSagas(clock.instant());
    }
}
