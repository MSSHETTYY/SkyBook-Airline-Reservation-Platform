package com.skybook.payment.support;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import com.skybook.common.security.Roles;
import com.skybook.common.test.TestJwtIssuer;

import io.restassured.RestAssured;

/** payment-service on a random port with the fake provider, H2 and real JWT validation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PaymentTestSupport.TestBeans.class)
public abstract class PaymentTestSupport {

    public static final TestJwtIssuer JWT = new TestJwtIssuer();
    public static final String WEBHOOK_SECRET = "whsec_test_secret";
    protected static final String SERVICE = "Bearer " + JWT.token("booking-service", Roles.SERVICE);

    @LocalServerPort
    private int port;

    @Autowired
    protected JdbcTemplate jdbc;

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestBeans {

        @Bean
        JwtDecoder jwtDecoder() {
            return JWT.decoder();
        }
    }

    @BeforeEach
    void configureRestAssured() {
        RestAssured.port = port;
    }

    protected static String bearer(String subject, String... roles) {
        return "Bearer " + JWT.token(subject, roles);
    }

    protected int outboxEvents(String eventType, String bookingReference) {
        Integer count = jdbc.queryForObject(
                "select count(*) from outbox_event where event_type = ? and message_key = ?", Integer.class,
                eventType, bookingReference);
        return count == null ? 0 : count;
    }

    protected static String nowSignatureTimestampFor(String payload) {
        return com.skybook.payment.provider.StripeSignatures.sign(payload, WEBHOOK_SECRET, Instant.now());
    }
}
