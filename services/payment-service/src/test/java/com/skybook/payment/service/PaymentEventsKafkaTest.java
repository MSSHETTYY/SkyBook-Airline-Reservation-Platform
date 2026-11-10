package com.skybook.payment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import com.skybook.common.money.Money;
import com.skybook.messaging.EventCodec;
import com.skybook.messaging.OutboxRelay;
import com.skybook.payment.domain.Payment;
import com.skybook.payment.domain.PaymentPurpose;
import com.skybook.payment.support.PaymentTestSupport;

/** From webhook to Kafka: the outbox relay publishes PaymentCompleted keyed by booking reference. */
@EmbeddedKafka(partitions = 1, topics = PaymentEvents.TOPIC)
class PaymentEventsKafkaTest extends PaymentTestSupport {

    @Autowired
    private PaymentService payments;

    @Autowired
    private PaymentSimulationService simulation;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private EventCodec codec;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    void paymentCompletedReachesKafka() {
        Payment payment = payments.create("KFKA22", PaymentPurpose.BOOKING, Money.of("120.00", "USD"),
                "kafka-test-key-1");
        simulation.simulate(payment.getId(), true);

        relay.relayPending();

        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "payment-events-test", false);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, PaymentEvents.TOPIC);
            // Other tests share the database and leave their own outbox events, which the relay publishes too:
            // look for this booking's PaymentCompleted instead of assuming it is the first record on the topic.
            ConsumerRecord<String, String> record = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (record == null && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> candidate : KafkaTestUtils.getRecords(consumer,
                        Duration.ofSeconds(2)).records(PaymentEvents.TOPIC)) {
                    if ("KFKA22".equals(candidate.key()) && candidate.value().contains("\"PaymentCompleted\"")) {
                        record = candidate;
                    }
                }
            }

            assertThat(record).as("PaymentCompleted for KFKA22 on " + PaymentEvents.TOPIC).isNotNull();
            var event = codec.decode(record.value(), PaymentEvents.PaymentCompleted.class);
            assertThat(event.eventType()).isEqualTo("PaymentCompleted");
            assertThat(event.payload().paymentId()).isEqualTo(payment.getId());
            assertThat(event.payload().amount()).isEqualByComparingTo(new BigDecimal("120.00"));
            assertThat(event.producer()).isEqualTo("payment-service");
        }
    }
}
