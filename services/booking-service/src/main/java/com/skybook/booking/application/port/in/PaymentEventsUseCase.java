package com.skybook.booking.application.port.in;

import com.skybook.common.money.Money;

/**
 * Driving port for payment events (from Kafka). The {@code record*} methods only change local state and
 * run inside the caller's de-duplication transaction; {@link #resume} then drives the remote saga steps.
 */
public interface PaymentEventsUseCase {

    void recordPaymentCompleted(String bookingReference, String paymentId, String purpose, Money amount);

    void recordRefundIssued(String bookingReference, Money amount);

    /** Continues whatever the booking's saga is waiting for; idempotent and safe to call repeatedly. */
    void resume(String bookingReference);
}
