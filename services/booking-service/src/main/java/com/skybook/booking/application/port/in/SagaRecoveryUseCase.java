package com.skybook.booking.application.port.in;

import java.time.Instant;

/** Driving port (scheduler): resume sagas that stopped progressing (crash, outage, lost message). */
public interface SagaRecoveryUseCase {

    /** Resumes stalled sagas and returns how many bookings were looked at. */
    int recoverStalledSagas(Instant now);
}
