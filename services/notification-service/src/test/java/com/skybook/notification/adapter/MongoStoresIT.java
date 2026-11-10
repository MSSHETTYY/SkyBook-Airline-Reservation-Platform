package com.skybook.notification.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.ToStringConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.mongodb.client.MongoClients;
import com.skybook.notification.domain.AuditRecord;

/**
 * The MongoDB adapters against a real MongoDB 8. Skipped without Docker.
 *
 * <p>A single standalone server, not Testcontainers' MongoDBContainer: the stores need no transactions, so the
 * replica-set initialisation that container performs adds start-up risk without testing anything here.
 */
@Testcontainers(disabledWithoutDocker = true)
class MongoStoresIT {

    /** Everything the server printed, kept even when it fails to start (the container is gone by then). */
    private static final ToStringConsumer SERVER_LOG = new ToStringConsumer();

    static final GenericContainer<?> MONGO = new GenericContainer<>("mongo:8.0.34")
            .withExposedPorts(27017)
            .withLogConsumer(SERVER_LOG)
            // Same matcher as Testcontainers' own MongoDB module (case-insensitive).
            .waitingFor(Wait.forLogMessage("(?i).*waiting for connections.*", 1)
                    .withStartupTimeout(Duration.ofMinutes(2)));

    @BeforeAll
    static void startServer() {
        try {
            MONGO.start();
        } catch (RuntimeException failure) {
            throw new AssertionError("MongoDB did not start. Server log tail: " + tail(), failure);
        }
    }

    @AfterAll
    static void stopServer() {
        MONGO.stop();
    }

    private static String tail() {
        String log = SERVER_LOG.toUtf8String();
        return log.substring(Math.max(0, log.length() - 1500));
    }

    @Test
    void auditTrailAndContactProjection() {
        try {
            verifyStores();
        } catch (RuntimeException | AssertionError failure) {
            throw new AssertionError("MongoDB log tail: " + tail(), failure);
        }
    }

    private void verifyStores() {
        String uri = "mongodb://" + MONGO.getHost() + ":" + MONGO.getMappedPort(27017);
        MongoTemplate mongo = new MongoTemplate(MongoClients.create(uri), "notifications");
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
