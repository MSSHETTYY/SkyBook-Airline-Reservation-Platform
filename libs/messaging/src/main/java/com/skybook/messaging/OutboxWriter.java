package com.skybook.messaging;

import java.sql.Timestamp;
import java.time.Clock;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.skybook.common.event.EventEnvelope;

/**
 * Writes domain events to the {@code outbox_event} table <b>in the caller's transaction</b>.
 *
 * <p>That is the whole point of the transactional outbox: the state change and the record of the event
 * commit or roll back together, so an event is never lost (state committed, publish failed) and never
 * published for a change that did not happen (published, then rolled back). {@link OutboxRelay}
 * publishes the rows to Kafka afterwards.
 */
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final EventCodec codec;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, EventCodec codec, Clock clock) {
        this.jdbc = jdbc;
        this.codec = codec;
        this.clock = clock;
    }

    /**
     * Appends an event for {@code topic}; the aggregate id becomes the Kafka key, so all events of one
     * aggregate land on one partition and are consumed in order.
     *
     * @throws IllegalStateException if called outside a transaction (the guarantee would be lost)
     */
    public void append(String topic, EventEnvelope<?> event) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Outbox events must be written inside the business transaction");
        }
        jdbc.update("""
                insert into outbox_event
                    (id, topic, message_key, event_type, correlation_id, payload, created_at, attempts)
                values (?, ?, ?, ?, ?, ?, ?, 0)""",
                event.eventId().toString(), topic, event.aggregateId(), event.eventType(), event.correlationId(),
                codec.encode(event), Timestamp.from(clock.instant()));
    }
}
