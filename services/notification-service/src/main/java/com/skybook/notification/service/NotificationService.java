package com.skybook.notification.service;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import com.skybook.notification.domain.AuditRecord;
import com.skybook.notification.domain.EmailMasking;
import com.skybook.notification.domain.NotificationType;
import com.skybook.notification.port.AuditStore;
import com.skybook.notification.port.ContactDirectory;
import com.skybook.notification.port.Mailer;

import io.github.resilience4j.retry.Retry;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns domain events into customer e-mails.
 *
 * <ol>
 *   <li>De-duplicate: an event already sent (or deliberately skipped) is acknowledged without action, so
 *       at-least-once delivery from Kafka and Solace never produces duplicate e-mails in the normal case.</li>
 *   <li>Learn contact addresses from booking events (local projection).</li>
 *   <li>Render the Thymeleaf template and send, retrying transient SMTP failures with backoff.</li>
 *   <li>Audit the outcome. A final failure is audited and rethrown, so the JMS transaction rolls back and
 *       the broker redelivers; after the broker's redelivery limit the message moves to the dead-message
 *       queue for operators.</li>
 * </ol>
 */
@Service
public class NotificationService {

    private static final Logger LOG = LoggerFactory.getLogger(NotificationService.class);

    private final AuditStore audit;
    private final ContactDirectory contacts;
    private final Mailer mailer;
    private final ITemplateEngine templates;
    private final ObjectMapper objectMapper;
    private final Retry mailRetry;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public NotificationService(AuditStore audit, ContactDirectory contacts, Mailer mailer, ITemplateEngine templates,
                               ObjectMapper objectMapper, Retry mailRetry, Clock clock) {
        this.audit = audit;
        this.contacts = contacts;
        this.mailer = mailer;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.mailRetry = mailRetry;
        this.clock = clock;
    }

    /** Handles one event (JSON envelope). Throws if the e-mail could not be sent, to trigger redelivery. */
    public void process(String eventJson) {
        JsonNode event = parse(eventJson);
        String eventId = event.path("eventId").asString();
        String eventType = event.path("eventType").asString();
        String correlationId = event.path("correlationId").asString(null);
        JsonNode payload = event.path("payload");
        String reference = payload.path("bookingReference").asString(null);

        if (payload.path("contactEmail").isString() && reference != null) {
            contacts.remember(reference, payload.path("contactEmail").asString());
        }
        Optional<NotificationType> type = NotificationType.forEvent(eventType);
        if (type.isEmpty() || reference == null) {
            return;
        }
        if (audit.isHandled(eventId)) {
            LOG.debug("Event {} already handled; skipping duplicate", eventId);
            return;
        }
        Optional<String> recipient = Optional.ofNullable(payload.path("contactEmail").asString(null))
                .or(() -> contacts.contactFor(reference));
        if (recipient.isEmpty()) {
            LOG.warn("No contact address known for booking {}; {} not sent", reference, eventType);
            audit.record(new AuditRecord(eventId, eventType, reference, null,
                    AuditRecord.Status.SKIPPED_NO_CONTACT, correlationId, clock.instant(), null));
            return;
        }
        String html = render(type.get(), reference, payload);
        try {
            String subject = type.get().subjectFor(reference);
            Retry.decorateRunnable(mailRetry, () -> mailer.send(recipient.get(), subject, html)).run();
        } catch (RuntimeException ex) {
            audit.record(new AuditRecord(eventId, eventType, reference, EmailMasking.mask(recipient.get()),
                    AuditRecord.Status.FAILED, correlationId, clock.instant(), String.valueOf(ex.getMessage())));
            LOG.error("Sending {} for booking {} failed; the message will be redelivered", eventType, reference);
            throw ex;
        }
        audit.record(new AuditRecord(eventId, eventType, reference, EmailMasking.mask(recipient.get()),
                AuditRecord.Status.SENT, correlationId, clock.instant(), null));
        LOG.info("Sent {} e-mail for booking {}", type.get(), reference);
    }

    String render(NotificationType type, String reference, JsonNode payload) {
        Context context = new Context(Locale.ENGLISH);
        @SuppressWarnings("unchecked")
        Map<String, Object> variables = objectMapper.convertValue(payload, Map.class);
        context.setVariables(variables);
        context.setVariable("bookingReference", reference);
        return templates.process(type.template(), context);
    }

    private JsonNode parse(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("Not a JSON event", ex);
        }
    }
}
