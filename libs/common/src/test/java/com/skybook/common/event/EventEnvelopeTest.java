package com.skybook.common.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

class EventEnvelopeTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-06T10:15:30Z"), ZoneOffset.UTC);

    record SeatsHeldPayload(String flightId, int seatCount) {
    }

    private static EventEnvelope<SeatsHeldPayload> rootEvent() {
        return EventEnvelope.builder(EventTypes.SEATS_HELD, new SeatsHeldPayload("SB101-2026-12-01", 2))
                .producer("inventory-service")
                .aggregate("Flight", "SB101-2026-12-01")
                .correlationId("corr-1")
                .build(CLOCK);
    }

    @Test
    void buildsRootEventWithDefaults() {
        EventEnvelope<SeatsHeldPayload> event = rootEvent();

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventVersion()).isEqualTo(1);
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-11-06T10:15:30Z"));
        assertThat(event.isRoot()).isTrue();
        assertThat(event.causationId()).isNull();
    }

    @Test
    void causedEventInheritsCorrelationAndPointsAtCause() {
        EventEnvelope<SeatsHeldPayload> cause = rootEvent();

        EventEnvelope<String> effect = cause.causing(EventTypes.BOOKING_CREATED, "booking-42")
                .producer("booking-service")
                .aggregate("Booking", "booking-42")
                .eventVersion(2)
                .build(CLOCK);

        assertThat(effect.correlationId()).isEqualTo(cause.correlationId());
        assertThat(effect.causationId()).isEqualTo(cause.eventId());
        assertThat(effect.eventId()).isNotEqualTo(cause.eventId());
        assertThat(effect.eventVersion()).isEqualTo(2);
        assertThat(effect.isRoot()).isFalse();
    }

    @Test
    void rejectsIncompleteMetadata() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventEnvelope.builder(EventTypes.SEATS_HELD, "p")
                        .aggregate("Flight", "f1").correlationId("c").build(CLOCK))
                .withMessageContaining("producer");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventEnvelope.builder(" ", "p")
                        .producer("svc").aggregate("Flight", "f1").correlationId("c").build(CLOCK))
                .withMessageContaining("eventType");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> EventEnvelope.builder(EventTypes.SEATS_HELD, "p").eventVersion(0)
                        .producer("svc").aggregate("Flight", "f1").correlationId("c").build(CLOCK))
                .withMessageContaining("eventVersion");
        assertThatNullPointerException()
                .isThrownBy(() -> EventEnvelope.builder(EventTypes.SEATS_HELD, null)
                        .producer("svc").aggregate("Flight", "f1").correlationId("c").build(CLOCK))
                .withMessageContaining("payload");
    }

    @Test
    void rejectsNullIdentityFields() {
        assertThatNullPointerException()
                .isThrownBy(() -> new EventEnvelope<>(null, "T", 1, Instant.EPOCH, "svc", "A", "1", "c", null, "p"))
                .withMessageContaining("eventId");
        assertThatNullPointerException()
                .isThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), "T", 1, null, "svc", "A", "1", "c", null, "p"))
                .withMessageContaining("occurredAt");
    }

    @Test
    void roundTripsThroughJson() {
        JsonMapper mapper = JsonMapper.builder().build();
        EventEnvelope<SeatsHeldPayload> original = rootEvent();

        String json = mapper.writeValueAsString(original);
        EventEnvelope<SeatsHeldPayload> restored =
                mapper.readValue(json, new TypeReference<EventEnvelope<SeatsHeldPayload>>() { });

        assertThat(json).contains("\"occurredAt\":\"2026-11-06T10:15:30Z\"");
        assertThat(restored).isEqualTo(original);
    }
}
