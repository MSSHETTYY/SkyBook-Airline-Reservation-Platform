package com.skybook.notification.port;

import java.util.List;

import com.skybook.notification.domain.AuditRecord;

/** Notification audit trail; also the de-duplication record for redelivered events. */
public interface AuditStore {

    /** Whether the event was already handled for good (sent or skipped); failed attempts do not count. */
    boolean isHandled(String eventId);

    /** Inserts or replaces the record for {@code record.eventId()}. */
    void record(AuditRecord record);

    List<AuditRecord> forBooking(String bookingReference);
}
