package com.skybook.payment.domain;

import com.skybook.common.error.ErrorCode;

/** Payment-specific error codes; part of the public API contract. */
public enum PaymentErrorCode implements ErrorCode {

    PAYMENT_NOT_REFUNDABLE(409, "Only successful payments can be refunded"),
    REFUND_EXCEEDS_PAYMENT(422, "Refund exceeds the refundable amount"),
    PAYMENT_NOT_CANCELLABLE(409, "The payment has already succeeded and cannot be cancelled"),
    INVALID_WEBHOOK_SIGNATURE(400, "Webhook signature verification failed"),
    PROVIDER_REJECTED(422, "The payment provider rejected the request"),
    SIMULATION_UNAVAILABLE(404, "Payment simulation is only available with the fake payment provider");

    private final int status;
    private final String title;

    PaymentErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
