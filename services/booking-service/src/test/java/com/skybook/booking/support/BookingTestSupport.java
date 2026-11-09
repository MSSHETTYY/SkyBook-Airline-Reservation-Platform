package com.skybook.booking.support;

import java.time.Instant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.skybook.common.test.MutableClock;
import com.skybook.common.test.TestJwtIssuer;

import io.restassured.RestAssured;

/** Full application on a random port, with inventory stubbed by WireMock and real JWT validation. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(BookingTestSupport.TestBeans.class)
public abstract class BookingTestSupport {

    public static final Instant START = Instant.parse("2026-12-01T08:00:00Z");
    public static final TestJwtIssuer JWT = new TestJwtIssuer();
    protected static final WireMockServer INVENTORY = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        INVENTORY.start();
    }

    @LocalServerPort
    private int port;

    @Autowired
    protected MutableClock clock;

    @DynamicPropertySource
    static void inventoryUrl(DynamicPropertyRegistry registry) {
        registry.add("skybook.booking.inventory.base-url", INVENTORY::baseUrl);
        for (String client : new String[] {"payment", "gds", "crm", "loyalty"}) {
            registry.add("skybook.booking.clients." + client + ".base-url", INVENTORY::baseUrl);
        }
    }

    @AfterAll
    static void resetStubs() {
        INVENTORY.resetAll();
    }

    @BeforeEach
    void resetState() {
        RestAssured.port = port;
        clock.set(START);
        INVENTORY.resetAll();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class TestBeans {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock(START);
        }

        @Bean
        JwtDecoder jwtDecoder() {
            return JWT.decoder();
        }
    }

    protected static String bearer(String subject, String... roles) {
        return "Bearer " + JWT.token(subject, roles);
    }
}
