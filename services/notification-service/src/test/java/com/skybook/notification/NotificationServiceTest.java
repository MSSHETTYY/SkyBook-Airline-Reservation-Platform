package com.skybook.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import com.skybook.common.event.EventEnvelope;
import com.skybook.notification.InMemoryStores.MemoryAuditStore;
import com.skybook.notification.InMemoryStores.MemoryContactDirectory;
import com.skybook.notification.domain.AuditRecord;
import com.skybook.notification.domain.EmailMasking;
import com.skybook.notification.port.Mailer;
import com.skybook.notification.service.NotificationService;

import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import tools.jackson.databind.json.JsonMapper;

/** Failure paths: SMTP outage, missing contact, malformed input. */
class NotificationServiceTest {

    private final MemoryAuditStore audit = new MemoryAuditStore();
    private final MemoryContactDirectory contacts = new MemoryContactDirectory();
    private final Mailer mailer = mock(Mailer.class);
    private final JsonMapper json = JsonMapper.builder().build();
    private final NotificationService service = new NotificationService(audit, contacts, mailer, templates(), json,
            Retry.of("mail", RetryConfig.custom().maxAttempts(3).waitDuration(Duration.ofMillis(1)).build()),
            Clock.systemUTC());

    private static SpringTemplateEngine templates() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private String event(String type, Map<String, Object> payload) {
        return json.writeValueAsString(EventEnvelope.builder(type, payload).producer("t")
                .aggregate("Booking", "NTFCCC").correlationId("c").build(Clock.systemUTC()));
    }

    @Test
    void smtpOutageIsRetriedThenAuditedAsFailedAndRethrownForRedelivery() {
        doThrow(new MailSendException("smtp down")).when(mailer).send(anyString(), anyString(), anyString());
        String event = event("BookingCancelled", Map.of("bookingReference", "NTFCCC", "contactEmail", "x@example.test",
                "reason", "CUSTOMER_REQUEST", "refundAmount", 0, "currency", "USD"));

        assertThatThrownBy(() -> service.process(event)).isInstanceOf(MailSendException.class);

        verify(mailer, times(3)).send(anyString(), anyString(), anyString());
        assertThat(audit.forBooking("NTFCCC")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(AuditRecord.Status.FAILED));
        assertThat(audit.isHandled(audit.forBooking("NTFCCC").getFirst().eventId()))
                .as("a failed attempt does not block the redelivery").isFalse();
    }

    @Test
    void unknownRecipientIsAuditedAndNotRetried() {
        service.process(event("PaymentFailed", Map.of("bookingReference", "NTFDDD", "reason", "declined",
                "paymentId", "pay_1", "purpose", "BOOKING")));

        verify(mailer, never()).send(anyString(), anyString(), anyString());
        assertThat(audit.forBooking("NTFDDD")).singleElement()
                .satisfies(r -> assertThat(r.status()).isEqualTo(AuditRecord.Status.SKIPPED_NO_CONTACT));
    }

    @Test
    void cancellationTemplatesExplainTheReasonAndTheRefund() {
        contacts.remember("NTFEEE", "y@example.test");
        service.process(event("BookingCancelled", Map.of("bookingReference", "NTFEEE", "reason", "TICKETING_FAILED",
                "refundAmount", 200.00, "currency", "USD")));

        verify(mailer).send(org.mockito.ArgumentMatchers.eq("y@example.test"),
                org.mockito.ArgumentMatchers.eq("Your booking NTFEEE has been cancelled"),
                org.mockito.ArgumentMatchers.argThat(html -> html.contains("could not issue your tickets")
                        && html.contains("200.0 USD")));
    }

    @Test
    void rejectsNonJsonAndMasksAddresses() {
        assertThatIllegalArgumentException().isThrownBy(() -> service.process("{nope"));
        assertThat(EmailMasking.mask("ada@example.test")).isEqualTo("a***@example.test");
        assertThat(EmailMasking.mask("broken")).isEqualTo("***");
        assertThat(EmailMasking.mask(" ")).isNull();
    }
}
