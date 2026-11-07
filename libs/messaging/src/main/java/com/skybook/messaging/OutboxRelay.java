package com.skybook.messaging;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.web.CorrelationId;

/**
 * Publishes outbox rows to Kafka (polling publisher).
 *
 * <p>Delivery is <b>at least once</b>: a row is marked published only after the broker acknowledged it
 * (producer with {@code acks=all} and idempotence). A crash between the acknowledgement and the update
 * re-sends the row on the next run, so consumers must be idempotent ({@link ProcessedEventStore}).
 *
 * <p>Ordering: rows are claimed and sent oldest first, and a batch stops at the first failure so a later
 * event of an aggregate is never published before an earlier one. Rows are claimed with
 * {@code FOR UPDATE SKIP LOCKED}, so several replicas can relay concurrently without double-sending
 * within a batch.
 */
public class OutboxRelay {

    private static final Logger LOG = LoggerFactory.getLogger(OutboxRelay.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;
    private final TransactionTemplate tx;
    private final MessagingProperties properties;
    private final Clock clock;

    public OutboxRelay(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka, TransactionTemplate tx,
                       MessagingProperties properties, Clock clock) {
        this.jdbc = jdbc;
        this.kafka = kafka;
        this.tx = tx;
        this.properties = properties;
        this.clock = clock;
    }

    private record PendingEvent(String id, String topic, String key, String eventType, String correlationId,
                                String payload) {
    }

    @Scheduled(fixedDelayString = "${skybook.messaging.relay-interval:PT0.5S}")
    public void relayPending() {
        int sent;
        do {
            sent = relayBatch();
        } while (sent == properties.relayBatchSize());
    }

    /** Publishes one batch; returns how many events were published. */
    public int relayBatch() {
        Integer published = tx.execute(status -> {
            List<PendingEvent> batch = jdbc.query("""
                    select id, topic, message_key, event_type, correlation_id, payload from outbox_event
                    where published_at is null order by created_at limit ?""" + " " + properties.lockClause(),
                    (rs, row) -> new PendingEvent(rs.getString("id"), rs.getString("topic"),
                            rs.getString("message_key"), rs.getString("event_type"), rs.getString("correlation_id"),
                            rs.getString("payload")),
                    properties.relayBatchSize());
            int count = 0;
            for (PendingEvent event : batch) {
                try {
                    send(event);
                    jdbc.update("update outbox_event set published_at = ?, attempts = attempts + 1 where id = ?",
                            Timestamp.from(clock.instant()), event.id());
                    count++;
                } catch (TimeoutException | ExecutionException | RuntimeException ex) {
                    String error = String.valueOf(ex.getMessage());
                    jdbc.update("update outbox_event set attempts = attempts + 1, last_error = ? where id = ?",
                            error.substring(0, Math.min(error.length(), 500)), event.id());
                    LOG.warn("Publishing outbox event {} ({}) failed; will retry: {}", event.id(), event.eventType(),
                            error);
                    break;
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            return count;
        });
        return published == null ? 0 : published;
    }

    /** Deletes published rows older than the retention period. */
    @Scheduled(fixedDelayString = "${skybook.messaging.cleanup-interval:PT1H}")
    public int deletePublishedBefore() {
        return jdbc.update("delete from outbox_event where published_at is not null and published_at < ?",
                Timestamp.from(clock.instant().minus(properties.retention())));
    }

    private void send(PendingEvent event) throws InterruptedException, ExecutionException, TimeoutException {
        ProducerRecord<String, String> record = new ProducerRecord<>(event.topic(), event.key(), event.payload());
        record.headers().add("eventType", event.eventType().getBytes(StandardCharsets.UTF_8));
        record.headers().add("eventId", event.id().getBytes(StandardCharsets.UTF_8));
        if (event.correlationId() != null) {
            record.headers().add(CorrelationId.HEADER, event.correlationId().getBytes(StandardCharsets.UTF_8));
        }
        kafka.send(record).get(properties.sendTimeout().toMillis(), TimeUnit.MILLISECONDS);
    }
}
