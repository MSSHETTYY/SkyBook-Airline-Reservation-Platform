package com.skybook.inventory.support;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;

import com.skybook.common.money.Money;
import com.skybook.common.test.MutableClock;
import com.skybook.common.test.TestJwtIssuer;
import com.skybook.inventory.service.FlightAdminService;
import com.skybook.inventory.service.FlightAdminService.CreateFlightCommand;

/**
 * Base class for Spring-context tests. Every test creates its own flight, so tests sharing the cached
 * application context (and database) never interfere with each other.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(InventoryTestSupport.ClockConfig.class)
public abstract class InventoryTestSupport {

    public static final Instant START = Instant.parse("2026-12-01T08:00:00Z");
    public static final TestJwtIssuer JWT = new TestJwtIssuer();

    private static final AtomicInteger FLIGHT_SEQUENCE = new AtomicInteger();

    @Autowired
    protected FlightAdminService admin;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetClock() {
        clock.set(START);
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class ClockConfig {

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

    /** Creates a flight 10+ days after the current test time: rows x seatLetters seats, first row business. */
    protected Long newFlight(int rows, String seatLetters) {
        int n = FLIGHT_SEQUENCE.incrementAndGet();
        Instant departure = clock.instant().plus(Duration.ofDays(10)).plus(Duration.ofMinutes(n));
        return admin.createFlight(new CreateFlightCommand("SB" + (1000 + n), "DXB", "LHR", departure,
                departure.plus(Duration.ofHours(7)), "A320", Money.of("100.00", "USD"), rows, seatLetters, 1),
                "flight-key-" + n + "-" + System.nanoTime()).flight().getId();
    }
}
