package com.skybook.payment.service;

import java.time.Clock;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.messaging.ProcessedEventStore;
import com.skybook.payment.config.PaymentProperties;
import com.skybook.payment.domain.PaymentErrorCode;
import com.skybook.payment.repository.PaymentRepository;
import com.skybook.payment.repository.RefundRepository;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.net.Webhook;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Handles Stripe webhooks: verify, de-duplicate, apply, publish.
 *
 * <ol>
 *   <li><b>Authenticity:</b> the {@code Stripe-Signature} HMAC is verified with the endpoint secret, with
 *       a 5-minute tolerance against replays. Unsigned or tampered requests are rejected with 400 before
 *       any parsing.</li>
 *   <li><b>Idempotency:</b> Stripe delivers at least once; the event id is recorded in the same
 *       transaction as the state change, so redeliveries are no-ops.</li>
 *   <li><b>Version independence:</b> after verification the raw JSON is read directly, rather than
 *       through the SDK's typed models, so a Stripe API version mismatch cannot break processing.</li>
 * </ol>
 * Events for unknown payments are acknowledged and ignored (they belong to another environment sharing
 * the Stripe account); throwing would make Stripe retry them for days.
 */
@Service
public class StripeWebhookService {

    static final String CONSUMER = "stripe-webhook";
    private static final Logger LOG = LoggerFactory.getLogger(StripeWebhookService.class);

    private final PaymentRepository payments;
    private final RefundRepository refunds;
    private final PaymentEvents events;
    private final ProcessedEventStore processed;
    private final PaymentProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public StripeWebhookService(PaymentRepository payments, RefundRepository refunds, PaymentEvents events,
                                ProcessedEventStore processed, PaymentProperties properties,
                                ObjectMapper objectMapper, Clock clock) {
        this.payments = payments;
        this.refunds = refunds;
        this.events = events;
        this.processed = processed;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public void handle(String payload, String signatureHeader) {
        verify(payload, signatureHeader);
        JsonNode event;
        try {
            event = objectMapper.readTree(payload);
        } catch (JacksonException ex) {
            throw new SkyBookException(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE, "Webhook payload is not JSON");
        }
        String eventId = event.path("id").asString();
        String type = event.path("type").asString();
        JsonNode object = event.path("data").path("object");
        boolean applied = processed.runOnce(CONSUMER, eventId, () -> dispatch(type, object));
        LOG.info("Stripe event {} ({}) {}", eventId, type, applied ? "processed" : "ignored as duplicate");
    }

    private void verify(String payload, String signatureHeader) {
        try {
            Webhook.Signature.verifyHeader(payload, signatureHeader, properties.webhookSecret(),
                    properties.webhookTolerance().toSeconds(), clock);
        } catch (SignatureVerificationException ex) {
            LOG.warn("Rejected Stripe webhook with an invalid signature: {}", ex.getMessage());
            throw new SkyBookException(PaymentErrorCode.INVALID_WEBHOOK_SIGNATURE,
                    "Webhook signature verification failed");
        }
    }

    private void dispatch(String type, JsonNode object) {
        String objectId = object.path("id").asString();
        switch (type) {
            case "payment_intent.succeeded" -> payments.findByProviderPaymentId(objectId).ifPresentOrElse(payment -> {
                Money captured = Money.ofMinorUnits(object.path("amount_received").asLong(),
                        object.path("currency").asString().toUpperCase(Locale.ROOT));
                if (payment.markSucceeded(captured, clock.instant())) {
                    events.paymentCompleted(payment);
                }
            }, () -> unknown(type, objectId));
            case "payment_intent.payment_failed" -> payments.findByProviderPaymentId(objectId).ifPresentOrElse(
                    payment -> {
                        String reason = object.path("last_payment_error").path("message").asString("Payment failed");
                        if (payment.recordFailedAttempt(reason, clock.instant())) {
                            events.paymentFailed(payment);
                        }
                    }, () -> unknown(type, objectId));
            case "payment_intent.canceled" -> payments.findByProviderPaymentId(objectId)
                    .ifPresentOrElse(payment -> payment.cancel(clock.instant()), () -> unknown(type, objectId));
            case "refund.created", "refund.updated" -> refunds.findByProviderRefundId(objectId).ifPresentOrElse(
                    refund -> {
                        String status = object.path("status").asString();
                        if ("succeeded".equals(status) && refund.markSucceeded(clock.instant())) {
                            events.refundIssued(refund);
                        } else if (("failed".equals(status) || "canceled".equals(status))
                                && refund.markFailed(clock.instant())) {
                            LOG.error("Refund {} failed at the provider; manual follow-up needed", refund.getId());
                        }
                    }, () -> unknown(type, objectId));
            default -> LOG.debug("Ignoring Stripe event type {}", type);
        }
    }

    private static void unknown(String type, String objectId) {
        LOG.warn("Stripe event {} references unknown object {}; acknowledged and ignored", type, objectId);
    }
}
