package com.skybook.booking.application.port.in;

import java.time.Instant;

/** Driving port (scheduler): expire bookings whose seat hold ran out before payment. */
public interface ExpireUnpaidBookingsUseCase {

    /** Expires up to one batch of overdue bookings and returns how many were expired. */
    int expireOverdue(Instant now);
}
