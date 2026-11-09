package com.skybook.notification.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;

import com.mongodb.client.MongoClients;
import com.skybook.notification.domain.AuditRecord;

/** The MongoDB adapters against a real MongoDB 8. Skipped without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class MongoStoresIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:8.0.34");

    @Test
    void auditTrailAndContactProjection() {
        MongoTemplate mongo = new MongoTemplate(MongoClients.create(MONGO.getConnectionString()), "notifications");
        MongoAuditStore audit = new MongoAuditStore(mongo);
        MongoContactDirectory contacts = new MongoContactDirectory(mongo);

        audit.record(new AuditRecord("evt-1", "TicketIssued", "NTFMMM", "a***@example.test",
                AuditRecord.Status.FAILED, "c", Instant.parse("2026-12-01T08:00:00Z"), "smtp down"));
        assertThat(audit.isHandled("evt-1")).isFalse();
        audit.record(new AuditRecord("evt-1", "TicketIssued", "NTFMMM", "a***@example.test",
                AuditRecord.Status.SENT, "c", Instant.parse("2026-12-01T08:00:05Z"), null));
        assertThat(audit.isHandled("evt-1")).isTrue();
        assertThat(audit.forBooking("NTFMMM")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(AuditRecord.Status.SENT));

        contacts.remember("NTFMMM", "ada@example.test");
        assertThat(contacts.contactFor("NTFMMM")).contains("ada@example.test");
        assertThat(contacts.contactFor("NOPE22")).isEmpty();
    }
}
