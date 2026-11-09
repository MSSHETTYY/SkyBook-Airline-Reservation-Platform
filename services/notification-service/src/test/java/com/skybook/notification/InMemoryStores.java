package com.skybook.notification;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.skybook.notification.domain.AuditRecord;
import com.skybook.notification.port.AuditStore;
import com.skybook.notification.port.ContactDirectory;

/** In-memory stores for tests without MongoDB ({@code skybook.notification.store=memory}). */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "skybook.notification.store", havingValue = "memory")
public class InMemoryStores {

    public static class MemoryAuditStore implements AuditStore {

        private final Map<String, AuditRecord> records = new ConcurrentHashMap<>();

        @Override
        public boolean isHandled(String eventId) {
            AuditRecord record = records.get(eventId);
            return record != null && record.isFinal();
        }

        @Override
        public void record(AuditRecord record) {
            records.put(record.eventId(), record);
        }

        @Override
        public List<AuditRecord> forBooking(String bookingReference) {
            return records.values().stream().filter(r -> bookingReference.equals(r.bookingReference())).toList();
        }
    }

    public static class MemoryContactDirectory implements ContactDirectory {

        private final Map<String, String> contacts = new ConcurrentHashMap<>();

        @Override
        public void remember(String bookingReference, String contactEmail) {
            contacts.put(bookingReference, contactEmail);
        }

        @Override
        public Optional<String> contactFor(String bookingReference) {
            return Optional.ofNullable(contacts.get(bookingReference));
        }
    }

    @Bean
    MemoryAuditStore auditStore() {
        return new MemoryAuditStore();
    }

    @Bean
    MemoryContactDirectory contactDirectory() {
        return new MemoryContactDirectory();
    }
}
