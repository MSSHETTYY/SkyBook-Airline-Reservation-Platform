package com.skybook.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;

import jakarta.jms.Connection;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.Message;
import jakarta.jms.MessageConsumer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import com.skybook.common.event.EventEnvelope;
import com.skybook.messaging.EventCodec;

/** Kafka in, JMS topic out: with an embedded Kafka broker and an embedded (Artemis) JMS broker. */
@SpringBootTest
@ActiveProfiles("test")
@EmbeddedKafka(partitions = 1, topics = {"skybook.booking.events", "skybook.payment.events",
        "skybook.booking.events.event-bridge.DLT", "skybook.payment.events.event-bridge.DLT"})
class EventForwarderTest {

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Autowired
    private EventCodec codec;

    @Test
    void forwardsEventsToHierarchicalTopicsWithMetadata() throws Exception {
        String event = codec.encode(EventEnvelope.builder("TicketIssued", Map.of("bookingReference", "K7QX2M"))
                .producer("booking-service").aggregate("Booking", "K7QX2M").correlationId("corr-bridge")
                .build(Clock.systemUTC()));

        try (Connection connection = connectionFactory.createConnection()) {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            MessageConsumer consumer = session.createConsumer(
                    session.createTopic("skybook/booking/TicketIssued/K7QX2M"));
            connection.start();

            ProducerRecord<String, String> record = new ProducerRecord<>("skybook.booking.events", "K7QX2M", event);
            record.headers().add("eventId", "evt-1".getBytes(StandardCharsets.UTF_8));
            record.headers().add("X-Correlation-Id", "corr-bridge".getBytes(StandardCharsets.UTF_8));
            kafka.send(record).get();

            Message message = consumer.receive(20_000);
            assertThat(message).isInstanceOf(TextMessage.class);
            assertThat(((TextMessage) message).getText()).isEqualTo(event);
            assertThat(message.getStringProperty("eventType")).isEqualTo("TicketIssued");
            assertThat(message.getStringProperty("eventId")).isEqualTo("evt-1");
            assertThat(message.getStringProperty("correlationId")).isEqualTo("corr-bridge");
        }
    }

    @Test
    void topicNamesAreDerivedFromDomainTypeAndKey() {
        assertThat(EventForwarder.topicFor("skybook.payment.events", "RefundIssued", "K7QX2M"))
                .isEqualTo("skybook/payment/RefundIssued/K7QX2M");
        assertThat(EventForwarder.topicFor("skybook.booking.events", "BookingCreated", null))
                .isEqualTo("skybook/booking/BookingCreated/unknown");
        assertThat(EventForwarder.topicFor("skybook.booking.events", "X", "a/b c"))
                .isEqualTo("skybook/booking/X/a_b_c");
        assertThatIllegalArgumentException().isThrownBy(() -> EventForwarder.topicFor("nodots", "X", "k"));
    }
}
