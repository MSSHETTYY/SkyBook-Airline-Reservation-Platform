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
            ConsumerRecord<String, String> record = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10))
                    .records(PaymentEvents.TOPIC).iterator().next();

            assertThat(record.key()).isEqualTo("KFKA22");
            var event = codec.decode(record.value(), PaymentEvents.PaymentCompleted.class);
            assertThat(event.eventType()).isEqualTo("PaymentCompleted");
            assertThat(event.payload().paymentId()).isEqualTo(payment.getId());
            assertThat(event.payload().amount()).isEqualByComparingTo(new BigDecimal("120.00"));
            assertThat(event.producer()).isEqualTo("payment-service");
        }
    }
}
