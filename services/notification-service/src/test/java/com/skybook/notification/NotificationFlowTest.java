package com.skybook.notification;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import jakarta.mail.internet.MimeMessage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.skybook.common.event.EventEnvelope;
import com.skybook.common.security.Roles;
import com.skybook.common.test.TestJwtIssuer;

import io.restassured.RestAssured;
import tools.jackson.databind.ObjectMapper;

/**
 * Event on the queue to e-mail in the inbox: an embedded JMS broker stands in for Solace (same JMS API),
 * GreenMail for the SMTP server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(NotificationFlowTest.TestBeans.class)
class NotificationFlowTest {

    private static final TestJwtIssuer JWT = new TestJwtIssuer();

    @RegisterExtension
    static final GreenMailExtension SMTP = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(GreenMailConfiguration.aConfig().withDisabledAuthentication());

    @TestConfiguration(proxyBeanMethods = false)
    static class TestBeans {

        @Bean
        JwtDecoder jwtDecoder() {
            return JWT.decoder();
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JmsTemplate jms;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    private String event(String type, Map<String, Object> payload) {
        return objectMapper.writeValueAsString(EventEnvelope.builder(type, payload)
                .producer("test").aggregate("Booking", String.valueOf(payload.get("bookingReference")))
                .correlationId("corr-notify").build(Clock.systemUTC()));
    }

    private MimeMessage[] awaitMessages(int count) {
        await().atMost(Duration.ofSeconds(20)).until(() -> SMTP.getReceivedMessages().length >= count);
        return SMTP.getReceivedMessages();
    }

    @Test
    void ticketIssuedSendsTheETicketsOnceEvenWhenRedelivered() throws Exception {
        String event = event("TicketIssued", Map.of("bookingReference", "NTFAAA", "contactEmail", "ada@example.test",
                "flightNumber", "SB101", "origin", "DXB", "destination", "LHR",
                "departureTime", "2026-12-11T08:00:00Z", "ticketNumbers", java.util.List.of("1761111111111")));

        jms.convertAndSend("q.notification", event);
        jms.convertAndSend("q.notification", event);

        MimeMessage message = awaitMessages(1)[0];
        assertThat(message.getSubject()).isEqualTo("Your e-tickets for booking NTFAAA");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("ada@example.test");
        assertThat(GreenMailUtil.getBody(message)).contains("1761111111111", "SB101");
        Thread.sleep(1_500);
        assertThat(SMTP.getReceivedMessages()).as("duplicate delivery sends nothing").hasSize(1);

        given().header("Authorization", "Bearer " + JWT.token("agent-1", Roles.AGENT))
                .queryParam("bookingReference", "NTFAAA")
                .when().get("/api/v1/admin/notifications")
                .then().statusCode(200)
                .body("[0].status", equalTo("SENT"))
                .body("[0].maskedRecipient", equalTo("a***@example.test"));
        given().header("Authorization", "Bearer " + JWT.token("cust-1", Roles.CUSTOMER))
                .queryParam("bookingReference", "NTFAAA")
                .when().get("/api/v1/admin/notifications")
                .then().statusCode(403);
    }

    @Test
    void paymentEventsFindTheirRecipientThroughTheContactProjection() {
        jms.convertAndSend("q.notification", event("BookingCreated", Map.of("bookingReference", "NTFBBB",
                "contactEmail", "alan@example.test", "flightNumber", "SB101", "origin", "DXB", "destination", "LHR",
                "departureTime", "2026-12-11T08:00:00Z", "totalAmount", 220.00, "currency", "USD",
                "paymentDueBy", "2026-12-01T08:15:00Z")));
        jms.convertAndSend("q.notification", event("RefundIssued", Map.of("bookingReference", "NTFBBB",
                "amount", 50.00, "currency", "USD", "refundId", "re_1", "paymentId", "pay_1", "reason", "MANUAL")));
        jms.convertAndSend("q.notification", event("SeatsHeld", Map.of("bookingReference", "NTFBBB")));

        MimeMessage[] messages = awaitMessages(2);
        assertThat(messages).extracting(m -> {
            try {
                return m.getSubject();
            } catch (jakarta.mail.MessagingException ex) {
                throw new IllegalStateException(ex);
            }
        }).contains("Complete your payment for booking NTFBBB", "Refund issued for booking NTFBBB");

        given().header("Authorization", "Bearer " + JWT.token("agent-1", Roles.AGENT))
                .queryParam("bookingReference", "NTFBBB")
                .when().get("/api/v1/admin/notifications")
                .then().statusCode(200)
                .body("eventType", hasItem("RefundIssued"));
    }
}
