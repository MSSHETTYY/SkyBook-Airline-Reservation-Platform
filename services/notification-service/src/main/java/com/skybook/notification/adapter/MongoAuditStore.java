package com.skybook.notification.adapter;

import java.time.Instant;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

import com.skybook.notification.domain.AuditRecord;
import com.skybook.notification.port.AuditStore;

/** Audit trail in MongoDB: one document per event (the event id is the document id, hence unique). */
@Component
@ConditionalOnProperty(name = "skybook.notification.store", havingValue = "mongo", matchIfMissing = true)
class MongoAuditStore implements AuditStore {

    @Document("notification_audit")
    record AuditDocument(@Id String eventId, String eventType, @Indexed String bookingReference,
                         String maskedRecipient, String status, String correlationId, Instant processedAt,
                         String error) {
    }

    private final MongoTemplate mongo;

    MongoAuditStore(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Override
    public boolean isHandled(String eventId) {
        AuditDocument document = mongo.findById(eventId, AuditDocument.class);
        return document != null && !AuditRecord.Status.FAILED.name().equals(document.status());
    }

    @Override
    public void record(AuditRecord r) {
        mongo.save(new AuditDocument(r.eventId(), r.eventType(), r.bookingReference(), r.maskedRecipient(),
                r.status().name(), r.correlationId(), r.processedAt(), r.error()));
    }

    @Override
    public List<AuditRecord> forBooking(String bookingReference) {
        Query query = Query.query(Criteria.where("bookingReference").is(bookingReference))
                .with(Sort.by("processedAt"));
        return mongo.find(query, AuditDocument.class).stream()
                .map(d -> new AuditRecord(d.eventId(), d.eventType(), d.bookingReference(), d.maskedRecipient(),
                        AuditRecord.Status.valueOf(d.status()), d.correlationId(), d.processedAt(), d.error()))
                .toList();
    }
}
