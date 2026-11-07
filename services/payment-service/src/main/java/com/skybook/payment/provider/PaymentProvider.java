package com.skybook.payment.provider;

import java.util.Map;

import com.skybook.common.money.Money;
import com.skybook.payment.domain.RefundStatus;

/**
 * Port to the payment service provider. Every mutating call carries an idempotency key that the provider
 * honours, so a retried call (timeout, crash between call and commit) never charges or refunds twice.
 *
 * <p>Implementations throw {@link ProviderUnavailableException} for transient failures (network, 5xx,
 * rate limiting) and {@link ProviderRejectedException} for permanent ones (invalid request).
 */
public interface PaymentProvider {

    ProviderIntent createIntent(Money amount, Map<String, String> metadata, String idempotencyKey);

    void cancelIntent(String providerPaymentId, String idempotencyKey);

    ProviderRefund refund(String providerPaymentId, Money amount, String idempotencyKey);

    /** A created payment intent: the provider's id and the client secret for the payment form. */
    record ProviderIntent(String id, String clientSecret) {
    }

    /** A created refund and its immediate status (Stripe refunds are often final at once). */
    record ProviderRefund(String id, RefundStatus status) {
    }
}
