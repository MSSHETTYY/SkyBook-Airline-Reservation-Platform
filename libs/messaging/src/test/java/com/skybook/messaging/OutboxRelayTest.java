package com.skybook.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.event.EventEnvelope;

import tools.jackson.databind.json.JsonMapper;

/** Relay failure semantics with a broker that fails on demand. */
class OutboxRelayTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-01T08:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private JdbcTemplate jdbc;
    private TransactionTemplate tx;
    private OutboxWriter writer;
    private OutboxRelay relay;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:relay-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("db/messaging-tables.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        MessagingProperties properties = new MessagingProperties(10, "FOR UPDATE", Duration.ofSeconds(1),
                Duration.ofDays(7), 3, Duration.ofMillis(10));
        writer = new OutboxWriter(jdbc, new EventCodec(JsonMapper.builder().build()), CLOCK);
        relay = new OutboxRelay(jdbc, kafka, tx, properties, CLOCK);
    }

    private void append(String aggregateId, Instant createdAt) {
        EventEnvelope<Map<String, String>> event = EventEnvelope.builder("Test", Map.of("k", "v"))
                .producer("test").aggregate("Agg", aggregateId).correlationId("c")
                .build(Clock.fixed(createdAt, ZoneOffset.UTC));
        tx.executeWithoutResult(status -> new OutboxWriter(jdbc, new EventCodec(JsonMapper.builder().build()),
                Clock.fixed(createdAt, ZoneOffset.UTC)).append("topic", event));
    }

    @Test
    void stopsAtTheFirstFailureToPreserveOrderAndRecordsTheError() {
        append("first", Instant.parse("2026-12-01T07:00:00Z"));
        append("second", Instant.parse("2026-12-01T07:00:01Z"));
        append("third", Instant.parse("2026-12-01T07:00:02Z"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int published = relay.relayBatch();

        assertThat(published).isEqualTo(1);
        verify(kafka, times(2)).send(any(ProducerRecord.class));
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select last_error from outbox_event where message_key = 'second'",
                String.class)).contains("broker down");
        assertThat(jdbc.queryForObject("select attempts from outbox_event where message_key = 'third'",
                Integer.class)).isZero();
    }

    @Test
    void nextRunRetriesTheFailedEvent() {
        append("only", Instant.parse("2026-12-01T07:00:00Z"));
        when(kafka.send(any(ProducerRecord.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("blip")))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        assertThat(relay.relayBatch()).isZero();
        relay.relayPending();

        assertThat(jdbc.queryForObject("select attempts from outbox_event where message_key = 'only'", Integer.class))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Integer.class))
                .isZero();
        assertThat(writer).isNotNull();
    }
}
