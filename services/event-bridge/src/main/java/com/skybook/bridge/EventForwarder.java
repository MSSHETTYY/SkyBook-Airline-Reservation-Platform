package com.skybook.bridge;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.skybook.common.web.CorrelationId;
import com.skybook.messaging.EventCodec;

/**
 * Forwards every booking and payment event from Kafka to a hierarchical Solace topic:
 * {@code skybook/<domain>/<EventType>/<bookingReference>}, e.g. {@code skybook/booking/TicketIssued/K7QX2M}.
 *
 * <p>The hierarchy lets consumers subscribe with wildcards ({@code skybook/>} for everything,
 * {@code skybook/payment/>} for payments only) without any change here. Delivery is at least once:
 * the Kafka offset is committed only after the persistent JMS send succeeded, so a crash re-forwards the
 * event and consumers de-duplicate by event id. Undeliverable records go to the bridge's dead-letter topic.
 */
@Component
class EventForwarder {

    static final String GROUP = "event-bridge";
    private static final Logger LOG = LoggerFactory.getLogger(EventForwarder.class);

    private final JmsTemplate jms;
    private final EventCodec codec;

    EventForwarder(JmsTemplate jms, EventCodec codec) {
        this.jms = jms;
        this.codec = codec;
    }

    @KafkaListener(topics = {"skybook.booking.events", "skybook.payment.events"}, groupId = GROUP)
    void forward(ConsumerRecord<String, String> record) {
        String eventType = codec.eventType(record.value());
        String destination = topicFor(record.topic(), eventType, record.key());
        jms.convertAndSend(destination, record.value(), message -> {
            message.setStringProperty("eventType", eventType);
            message.setStringProperty("eventId", header(record, "eventId"));
            message.setStringProperty("correlationId", header(record, CorrelationId.HEADER));
            return message;
        });
        LOG.debug("Forwarded {} for {} to {}", eventType, record.key(), destination);
    }

    /** Maps e.g. topic {@code skybook.booking.events}, type TicketIssued, key K7QX2M to the Solace topic. */
    static String topicFor(String kafkaTopic, String eventType, String key) {
        String[] parts = kafkaTopic.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Unexpected topic " + kafkaTopic);
        }
        String domain = parts[1].toLowerCase(Locale.ROOT);
        String safeKey = key == null || key.isBlank() ? "unknown" : key.replaceAll("[^A-Za-z0-9_-]", "_");
        return "skybook/" + domain + "/" + eventType + "/" + safeKey;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
