package com.skybook.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.event.EventEnvelope;

@SpringBootTest(classes = MessagingIntegrationTest.TestApp.class)
@EmbeddedKafka(partitions = 1, topics = {"test.events", "test.poison", "test.poison.messaging-test.DLT"})
class MessagingIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-12-01T08:00:00Z");

    @SpringBootApplication
    static class TestApp {

        static final AtomicInteger POISON_ATTEMPTS = new AtomicInteger();

        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

        @KafkaListener(topics = "test.poison", groupId = "messaging-test")
        void poison(String value) {
            POISON_ATTEMPTS.incrementAndGet();
            throw new EventDecodingException("cannot parse " + value, null);
        }
    }

    record SeatsHeld(String flight, int seats) {
    }

    @Autowired
    private OutboxWriter outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private ProcessedEventStore processed;

    @Autowired
    private EventCodec codec;

    @Autowired
    private TransactionTemplate tx;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Autowired
    private KafkaTemplate<String, String> kafka;

    private static EventEnvelope<SeatsHeld> event(String aggregateId) {
        return EventEnvelope.builder("SeatsHeld", new SeatsHeld("SB101", 2))
                .producer("test").aggregate("Booking", aggregateId).correlationId("corr-1")
                .build(Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Consumer<String, String> consumer(String group, String topic) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, group, false);
        Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(),
                new StringDeserializer()).createConsumer();
        broker.consumeFromAnEmbeddedTopic(consumer, topic);
        return consumer;
    }

    @Test
    void committedEventsArePublishedWithKeyAndHeaders() {
        EventEnvelope<SeatsHeld> event = event("K7QX2M");
        tx.executeWithoutResult(status -> outbox.append("test.events", event));

        assertThat(relay.relayBatch()).isGreaterThanOrEqualTo(1);

        try (Consumer<String, String> consumer = consumer("verify-1", "test.events")) {
            ConsumerRecord<String, String> record = KafkaTestUtils.getSingleRecord(consumer, "test.events",
                    Duration.ofSeconds(10));
            assertThat(record.key()).isEqualTo("K7QX2M");
            assertThat(new String(record.headers().lastHeader("eventType").value(), StandardCharsets.UTF_8))
                    .isEqualTo("SeatsHeld");
            assertThat(new String(record.headers().lastHeader("X-Correlation-Id").value(), StandardCharsets.UTF_8))
                    .isEqualTo("corr-1");
            EventEnvelope<SeatsHeld> decoded = codec.decode(record.value(), SeatsHeld.class);
            assertThat(decoded).isEqualTo(event);
        }
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where published_at is null "
                + "and id = ?", Integer.class, event.eventId().toString())).isZero();
    }

    @Test
    void rolledBackTransactionsLeaveNoEvent() {
        EventEnvelope<SeatsHeld> event = event("ROLLBK");

        tx.executeWithoutResult(status -> {
            outbox.append("test.events", event);
            status.setRollbackOnly();
        });

        assertThat(jdbc.queryForObject("select count(*) from outbox_event where id = ?", Integer.class,
                event.eventId().toString())).isZero();
    }

    @Test
    void appendingOutsideATransactionIsRefused() {
        assertThatIllegalStateException().isThrownBy(() -> outbox.append("test.events", event("NOTX22")))
                .withMessageContaining("inside the business transaction");
    }

    @Test
    void duplicatesAreProcessedOnceAndFailuresAreRetried() {
        EventEnvelope<SeatsHeld> event = event("DUPLIC");
        AtomicInteger runs = new AtomicInteger();

        assertThat(processed.handleOnce("consumer-a", event, e -> runs.incrementAndGet())).isTrue();
        assertThat(processed.handleOnce("consumer-a", event, e -> runs.incrementAndGet())).isFalse();
        assertThat(processed.handleOnce("consumer-b", event, e -> runs.incrementAndGet()))
                .as("other consumers process it independently").isTrue();
        assertThat(runs).hasValue(2);

        EventEnvelope<SeatsHeld> failing = event("FAILNG");
        try {
            processed.handleOnce("consumer-a", failing, e -> {
                throw new IllegalStateException("downstream failed");
            });
        } catch (IllegalStateException expected) {
            assertThat(expected).hasMessage("downstream failed");
        }
        assertThat(processed.handleOnce("consumer-a", failing, e -> runs.incrementAndGet()))
                .as("the marker was rolled back with the failure").isTrue();
    }

    @Test
    void poisonRecordsSkipRetriesAndLandOnTheConsumersDeadLetterTopic() throws Exception {
        kafka.send("test.poison", "key-1", "{not json").get();

        try (Consumer<String, String> consumer = consumer("verify-dlt", "test.poison.messaging-test.DLT")) {
            ConsumerRecord<String, String> dead = KafkaTestUtils.getSingleRecord(consumer,
                    "test.poison.messaging-test.DLT", Duration.ofSeconds(20));
            assertThat(dead.value()).isEqualTo("{not json");
            assertThat(dead.headers().lastHeader("kafka_dlt-exception-fqcn")).isNotNull();
        }
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(TestApp.POISON_ATTEMPTS).as("not retried").hasValue(1));
    }

    @Test
    void publishedRowsAreCleanedUpAfterTheRetentionPeriod() {
        jdbc.update("insert into outbox_event (id, topic, message_key, event_type, payload, created_at, published_at, "
                        + "attempts) values (?, 't', 'k', 'E', '{}', ?, ?, 1)", "00000000-0000-0000-0000-00000000c1ea",
                Timestamp.from(NOW.minus(Duration.ofDays(30))), Timestamp.from(NOW.minus(Duration.ofDays(30))));

        assertThat(relay.deletePublishedBefore()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForList("select id from outbox_event where id = '00000000-0000-0000-0000-00000000c1ea'"))
                .isEmpty();
    }

    @Test
    void decoderRejectsIncompleteEvents() {
        assertThatThrownBy(() -> codec.decode("{\"eventType\":\"X\"}", Map.class))
                .isInstanceOf(EventDecodingException.class);
        assertThatThrownBy(() -> codec.eventType("{oops"))
                .isInstanceOf(EventDecodingException.class);
        assertThat(codec.eventType(codec.encode(event("TYPE22")))).isEqualTo("SeatsHeld");
        assertThat(DeadLetterSupport.deadLetterTopic("a.b", "grp")).isEqualTo("a.b.grp.DLT");
    }
}
