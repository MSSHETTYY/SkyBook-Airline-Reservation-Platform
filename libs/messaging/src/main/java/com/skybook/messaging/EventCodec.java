package com.skybook.messaging;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

import com.skybook.common.event.EventEnvelope;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * JSON wire format of domain events: the {@link EventEnvelope} with its payload nested under
 * {@code payload}. Consumers decode the envelope first and bind the payload to their own type, so a
 * producer can add payload fields without breaking consumers (they ignore unknown properties).
 */
public class EventCodec {

    private final ObjectMapper objectMapper;

    public EventCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(EventEnvelope<?> event) {
        return objectMapper.writeValueAsString(event);
    }

    /** Decodes the envelope and binds its payload to {@code payloadType}. */
    public <T> EventEnvelope<T> decode(String json, Class<T> payloadType) {
        try {
            JsonNode root = objectMapper.readTree(json);
            T payload = objectMapper.treeToValue(required(root, "payload"), payloadType);
            JsonNode causation = root.path("causationId");
            return new EventEnvelope<>(
                    UUID.fromString(required(root, "eventId").asString()),
                    required(root, "eventType").asString(),
                    required(root, "eventVersion").asInt(),
                    Instant.parse(required(root, "occurredAt").asString()),
                    required(root, "producer").asString(),
                    required(root, "aggregateType").asString(),
                    required(root, "aggregateId").asString(),
                    required(root, "correlationId").asString(),
                    causation.isMissingNode() || causation.isNull() ? null : UUID.fromString(causation.asString()),
                    payload);
        } catch (JacksonException | IllegalArgumentException | DateTimeParseException ex) {
            throw new EventDecodingException("Cannot decode event: " + ex.getMessage(), ex);
        }
    }

    /** Reads only the event type, to route a record before binding its payload. */
    public String eventType(String json) {
        try {
            return required(objectMapper.readTree(json), "eventType").asString();
        } catch (JacksonException ex) {
            throw new EventDecodingException("Cannot read event type: " + ex.getMessage(), ex);
        }
    }

    private static JsonNode required(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isMissingNode() || node.isNull()) {
            throw new EventDecodingException("Event is missing '" + field + "'", null);
        }
        return node;
    }
}
