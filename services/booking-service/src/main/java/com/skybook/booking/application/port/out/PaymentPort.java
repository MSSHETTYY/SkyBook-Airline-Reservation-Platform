package com.skybook.booking.application.port.out;

import com.skybook.common.money.Money;

/**
 * Driven port: payment-service. All operations are idempotent per idempotency key, so the saga can retry
 * them after a crash or timeout without charging or refunding twice.
 */
public interface PaymentPort {

    PaymentIntent createPayment(String bookingReference, Money amount, Purpose purpose, String idempotencyKey);

    /** Abandons an unpaid payment (best effort; the payment simply stays unpaid if this fails). */
    void cancelPayment(String paymentId);

    /** Refunds {@code amount} for the booking; payment-service spreads it over the booking's payments. */
    void refundBooking(String bookingReference, Money amount, RefundReason reason, String idempotencyKey);

    /** A payment the customer completes in the browser with the client secret (Stripe Elements). */
    record PaymentIntent(String paymentId, String clientSecret) {
    }

    enum Purpose {
        BOOKING, DATE_CHANGE
    }

    enum RefundReason {
        CUSTOMER_CANCELLATION, DATE_CHANGE_DIFFERENCE, TICKETING_FAILED, MANUAL
    }
}
