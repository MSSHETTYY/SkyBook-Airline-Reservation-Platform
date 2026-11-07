package com.skybook.messaging;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.event.EventEnvelope;

/**
 * Idempotent consumption: remembers which events a consumer has processed ({@code processed_event}
 * table, primary key consumer + event id) <b>in the same transaction</b> as the business change.
 *
 * <p>A redelivered event (at-least-once delivery, consumer rebalance, outbox re-send) hits the primary
 * key and is skipped; a failure rolls back both the business change and the marker, so the retry
 * processes the event again from scratch.
 */
public class ProcessedEventStore {

    private static final Logger LOG = LoggerFactory.getLogger(ProcessedEventStore.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;

    public ProcessedEventStore(JdbcTemplate jdbc, TransactionTemplate tx, Clock clock) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.clock = clock;
    }

    /**
     * Runs {@code action} once per (consumer, event) in a single transaction with the marker insert.
     *
     * @return {@code true} if the action ran, {@code false} if the event was a duplicate
     */
    public <T> boolean handleOnce(String consumer, EventEnvelope<T> event, Consumer<EventEnvelope<T>> action) {
        Boolean processed = tx.execute(status -> {
            if (!markProcessed(consumer, event.eventId())) {
                LOG.debug("Skipping duplicate event {} ({}) for {}", event.eventId(), event.eventType(), consumer);
                return false;
            }
            action.accept(event);
            return true;
        });
        return Boolean.TRUE.equals(processed);
    }

    /**
     * Runs {@code action} once per (consumer, external event id), e.g. a Stripe webhook event, in one
     * transaction with the marker insert.
     */
    public boolean runOnce(String consumer, String externalEventId, Runnable action) {
        Boolean processed = tx.execute(status -> {
            if (!markProcessed(consumer, externalEventId)) {
                LOG.debug("Skipping duplicate event {} for {}", externalEventId, consumer);
                return false;
            }
            action.run();
            return true;
        });
        return Boolean.TRUE.equals(processed);
    }

    boolean markProcessed(String consumer, UUID eventId) {
        return markProcessed(consumer, eventId.toString());
    }

    boolean markProcessed(String consumer, String eventId) {
        try {
            jdbc.update("insert into processed_event (consumer, event_id, processed_at) values (?, ?, ?)",
                    consumer, eventId, Timestamp.from(clock.instant()));
            return true;
        } catch (DuplicateKeyException duplicate) {
            return false;
        }
    }
}
