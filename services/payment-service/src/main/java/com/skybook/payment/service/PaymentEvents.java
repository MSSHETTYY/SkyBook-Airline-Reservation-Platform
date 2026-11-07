package com.skybook.payment.service;

import java.math.BigDecimal;
import java.time.Clock;

import org.springframework.stereotype.Component;

import com.skybook.common.event.EventEnvelope;
import com.skybook.common.event.EventTypes;
import com.skybook.common.web.CorrelationId;
import com.skybook.messaging.OutboxWriter;
import com.skybook.payment.domain.Payment;
import com.skybook.payment.domain.PaymentPurpose;
import com.skybook.payment.domain.Refund;
import com.skybook.payment.domain.RefundReason;

/**
 * Payment domain events, written to the outbox in the caller's transaction.
 *
 * <p>All events are keyed by booking reference ({@code aggregateType = "Booking"}): a booking's payment,
 * date-change payment and refunds therefore share a partition and are consumed in order. Payloads carry no
 * personal data (minimal-PII event design).
 */
@Component
public class PaymentEvents {

    public static final String TOPIC = "skybook.payment.events";
    static final String PRODUCER = "payment-service";

    /** A payment was captured. */
    public record PaymentCompleted(String paymentId, String bookingReference, PaymentPurpose purpose,
                                   BigDecimal amount, String currency) {
    }

    /** A card attempt failed; the payment can still be completed. */
    public record PaymentFailed(String paymentId, String bookingReference, PaymentPurpose purpose, String reason) {
    }

    /** Money was returned to the customer. */
    public record RefundIssued(String refundId, String paymentId, String bookingReference, BigDecimal amount,
                               String currency, RefundReason reason) {
    }

    private final OutboxWriter outbox;
    private final Clock clock;

    public PaymentEvents(OutboxWriter outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    void paymentCompleted(Payment payment) {
        publish(EventTypes.PAYMENT_COMPLETED, payment, new PaymentCompleted(payment.getId(),
                payment.getBookingReference(), payment.getPurpose(), payment.amount().amount(),
                payment.amount().currencyCode()));
    }

    void paymentFailed(Payment payment) {
        publish(EventTypes.PAYMENT_FAILED, payment, new PaymentFailed(payment.getId(), payment.getBookingReference(),
                payment.getPurpose(), payment.getFailureReason()));
    }

    void refundIssued(Refund refund) {
        Payment payment = refund.getPayment();
        publish(EventTypes.REFUND_ISSUED, payment, new RefundIssued(refund.getId(), payment.getId(),
                payment.getBookingReference(), refund.amount().amount(), refund.amount().currencyCode(),
                refund.getReason()));
    }

    private void publish(String type, Payment payment, Object payload) {
        String correlationId = payment.getCorrelationId() != null ? payment.getCorrelationId()
                : CorrelationId.current().orElseGet(CorrelationId::generate);
        outbox.append(TOPIC, EventEnvelope.builder(type, payload)
                .producer(PRODUCER)
                .aggregate("Booking", payment.getBookingReference())
                .correlationId(correlationId)
                .build(clock));
    }
}
