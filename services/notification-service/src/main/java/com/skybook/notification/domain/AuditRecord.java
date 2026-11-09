package com.skybook.notification.domain;

import java.time.Instant;

/**
 * One line of the notification audit trail: what was sent (or not), to whom, and why. The recipient is
 * stored masked ({@code a***@example.test}): enough for support to recognise it, useless if leaked.
 */
public record AuditRecord(String eventId, String eventType, String bookingReference, String maskedRecipient,
                          Status status, String correlationId, Instant processedAt, String error) {

    public enum Status {
        SENT, FAILED, SKIPPED_NO_CONTACT
    }

    /** Whether the event needs no further delivery attempts. */
    public boolean isFinal() {
        return status != Status.FAILED;
    }
}
