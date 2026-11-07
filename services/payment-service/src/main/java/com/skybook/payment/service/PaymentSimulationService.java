package com.skybook.payment.service;

import java.time.Clock;
import java.util.Locale;
import java.util.UUID;

import com.skybook.payment.config.PaymentProperties;
import com.skybook.payment.domain.Payment;
import com.skybook.payment.provider.StripeSignatures;

/**
 * Completes or fails a payment when the fake provider is active, by producing the exact Stripe event the
 * real provider would send, signing it with the webhook secret and feeding it through
 * {@link StripeWebhookService}. The local and E2E flow therefore exercises the production webhook path
 * (signature check, de-duplication, outbox) rather than a shortcut.
 */
public class PaymentSimulationService {

    private final PaymentService payments;
    private final StripeWebhookService webhooks;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentSimulationService(PaymentService payments, StripeWebhookService webhooks,
                                    PaymentProperties properties, Clock clock) {
        this.payments = payments;
        this.webhooks = webhooks;
        this.properties = properties;
        this.clock = clock;
    }

    public void simulate(String paymentId, boolean succeeded) {
        Payment payment = payments.get(paymentId);
        String type = succeeded ? "payment_intent.succeeded" : "payment_intent.payment_failed";
        String lastError = succeeded ? "null" : "{\"message\":\"Your card was declined (simulated).\"}";
        String payload = """
                {"id":"evt_fake_%s","object":"event","type":"%s","data":{"object":{"id":"%s","object":"payment_intent",\
                "amount_received":%d,"currency":"%s","last_payment_error":%s}}}"""
                .formatted(UUID.randomUUID().toString().replace("-", ""), type, payment.getProviderPaymentId(),
                        succeeded ? payment.amount().minorUnits() : 0,
                        payment.amount().currencyCode().toLowerCase(Locale.ROOT), lastError);
        webhooks.handle(payload, StripeSignatures.sign(payload, properties.webhookSecret(), clock.instant()));
    }
}
