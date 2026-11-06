package com.skybook.common.event;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Transport-neutral wrapper for every domain event SkyBook publishes (Kafka, Solace, outbox rows).
 *
 * <p>Metadata lives in the envelope so consumers can deduplicate, route, trace and evolve schemas
 * without inspecting the payload:
 * <ul>
 *   <li>{@code eventId} - globally unique; idempotent consumers record it to drop redeliveries.</li>
 *   <li>{@code eventType} + {@code eventVersion} - select the payload schema; a breaking payload
 *       change bumps the version rather than mutating the existing one.</li>
 *   <li>{@code aggregateType} + {@code aggregateId} - the Kafka message key, which keeps all events
 *       of one aggregate on one partition and therefore in order.</li>
 *   <li>{@code correlationId} - shared by every event of one business interaction.</li>
 *   <li>{@code causationId} - the {@code eventId} of the event that directly caused this one, which
 *       makes saga flows reconstructable from the audit trail.</li>
 * </ul>
 *
 * <p>This type intentionally has no framework dependencies (enforced by an ArchUnit rule), so it
 * serialises with any JSON mapper and can be shared with non-Spring consumers.
 *
 * @param <T> payload type
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        String producer,
        String aggregateType,
        String aggregateId,
        String correlationId,
        UUID causationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        requireText(eventType, "eventType");
        if (eventVersion < 1) {
            throw new IllegalArgumentException("eventVersion must be >= 1 but was " + eventVersion);
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        requireText(producer, "producer");
        requireText(aggregateType, "aggregateType");
        requireText(aggregateId, "aggregateId");
        requireText(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
    }

    /** Starts building a root event, i.e. one not caused by another event (e.g. triggered by an API call). */
    public static <T> Builder<T> builder(String eventType, T payload) {
        return new Builder<>(eventType, payload);
    }

    /**
     * Starts building an event caused by this one: the correlation ID is inherited and the causation
     * ID points at this event.
     */
    public <R> Builder<R> causing(String nextEventType, R nextPayload) {
        return new Builder<>(nextEventType, nextPayload)
                .correlationId(correlationId)
                .causationId(eventId);
    }

    /** Whether this event started a causal chain. */
    public boolean isRoot() {
        return causationId == null;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    /** Fluent builder; {@code eventId} and {@code occurredAt} are assigned at {@link #build(Clock)} time. */
    public static final class Builder<T> {

        private final String eventType;
        private final T payload;
        private int eventVersion = 1;
        private String producer;
        private String aggregateType;
        private String aggregateId;
        private String correlationId;
        private UUID causationId;

        private Builder(String eventType, T payload) {
            this.eventType = eventType;
            this.payload = payload;
        }

        public Builder<T> eventVersion(int version) {
            this.eventVersion = version;
            return this;
        }

        public Builder<T> producer(String serviceName) {
            this.producer = serviceName;
            return this;
        }

        public Builder<T> aggregate(String type, String id) {
            this.aggregateType = type;
            this.aggregateId = id;
            return this;
        }

        public Builder<T> correlationId(String id) {
            this.correlationId = id;
            return this;
        }

        public Builder<T> causationId(UUID id) {
            this.causationId = id;
            return this;
        }

        /** Builds the envelope, taking the timestamp from the supplied clock (injectable for tests). */
        public EventEnvelope<T> build(Clock clock) {
            return new EventEnvelope<>(UUID.randomUUID(), eventType, eventVersion, Instant.now(clock), producer,
                    aggregateType, aggregateId, correlationId, causationId, payload);
        }
    }
}
