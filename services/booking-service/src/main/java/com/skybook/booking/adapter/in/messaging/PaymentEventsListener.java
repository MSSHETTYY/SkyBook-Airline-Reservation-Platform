package com.skybook.booking.adapter.in.messaging;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.skybook.booking.application.port.in.PaymentEventsUseCase;
import com.skybook.common.event.EventEnvelope;
import com.skybook.common.event.EventTypes;
import com.skybook.common.money.Money;
import com.skybook.common.web.CorrelationId;
import com.skybook.messaging.EventCodec;
import com.skybook.messaging.ProcessedEventStore;

/**
 * Driving adapter: consumes payment-service events.
 *
 * <p>Two phases per event: (1) the local state change runs exactly once per event id, in one transaction
 * with the de-duplication marker; (2) the saga is resumed. Phase 2 is idempotent and runs even for a
 * duplicate delivery, so a crash between the phases is healed by the redelivery (or by the recovery job).
 * Failures propagate to the container's error handler: retries with backoff, then the dead-letter topic.
 */
@Component
class PaymentEventsListener {

    static final String CONSUMER = "booking-service";
    static final String TOPIC = "skybook.payment.events";
    private static final Logger LOG = LoggerFactory.getLogger(PaymentEventsListener.class);

    record PaymentCompleted(String paymentId, String bookingReference, String purpose, BigDecimal amount,
                            String currency) {
    }

    record RefundIssued(String refundId, String paymentId, String bookingReference, BigDecimal amount,
                        String currency, String reason) {
    }

    private final PaymentEventsUseCase saga;
    private final ProcessedEventStore processed;
    private final EventCodec codec;

    PaymentEventsListener(PaymentEventsUseCase saga, ProcessedEventStore processed, EventCodec codec) {
        this.saga = saga;
        this.processed = processed;
        this.codec = codec;
    }

    @KafkaListener(topics = TOPIC, groupId = CONSUMER)
    void on(String record) {
        String type = codec.eventType(record);
        switch (type) {
            case EventTypes.PAYMENT_COMPLETED -> {
                EventEnvelope<PaymentCompleted> event = codec.decode(record, PaymentCompleted.class);
                withCorrelation(event, () -> {
                    PaymentCompleted p = event.payload();
                    processed.handleOnce(CONSUMER, event, e -> saga.recordPaymentCompleted(p.bookingReference(),
                            p.paymentId(), p.purpose(), Money.of(p.amount(), p.currency())));
                    saga.resume(p.bookingReference());
                });
            }
            case EventTypes.REFUND_ISSUED -> {
                EventEnvelope<RefundIssued> event = codec.decode(record, RefundIssued.class);
                withCorrelation(event, () -> processed.handleOnce(CONSUMER, event, e -> saga.recordRefundIssued(
                        e.payload().bookingReference(), Money.of(e.payload().amount(), e.payload().currency()))));
            }
            default -> LOG.debug("Ignoring payment event {}", type);
        }
    }

    /** Continues the saga's correlation ID, so logs and follow-up events of this step stay correlated. */
    private static void withCorrelation(EventEnvelope<?> event, Runnable action) {
        try (MDC.MDCCloseable ignored = MDC.putCloseable(CorrelationId.MDC_KEY, event.correlationId())) {
            action.run();
        }
    }
}
