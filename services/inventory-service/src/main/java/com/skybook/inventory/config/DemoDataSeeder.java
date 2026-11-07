package com.skybook.inventory.config;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.skybook.common.money.Money;
import com.skybook.inventory.service.FlightAdminService;
import com.skybook.inventory.service.FlightAdminService.CreateFlightCommand;

/**
 * Seeds a realistic schedule for local demos ({@code --spring.profiles.active=demo}). Runs through the
 * normal admin use case, and is idempotent because every flight is created with a deterministic key.
 */
@Component
@Profile("demo")
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final int DAYS = 14;

    private record Route(String flightNumber, String origin, String destination, LocalTime departure,
                         Duration duration, String fare) {
    }

    private static final List<Route> ROUTES = List.of(
            new Route("SB101", "DXB", "LHR", LocalTime.of(8, 0), Duration.ofHours(7).plusMinutes(30), "420.00"),
            new Route("SB102", "LHR", "DXB", LocalTime.of(14, 0), Duration.ofHours(7), "410.00"),
            new Route("SB201", "DXB", "BOM", LocalTime.of(9, 30), Duration.ofHours(3), "180.00"),
            new Route("SB202", "BOM", "DXB", LocalTime.of(15, 0), Duration.ofHours(3).plusMinutes(15), "175.00"),
            new Route("SB301", "LHR", "JFK", LocalTime.of(11, 0), Duration.ofHours(8), "520.00"));

    private final FlightAdminService admin;
    private final Clock clock;

    public DemoDataSeeder(FlightAdminService admin, Clock clock) {
        this.admin = admin;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        LocalDate today = LocalDate.now(clock);
        int created = 0;
        for (int day = 1; day <= DAYS; day++) {
            LocalDate date = today.plusDays(day);
            for (Route route : ROUTES) {
                Instant departure = date.atTime(route.departure()).toInstant(ZoneOffset.UTC);
                admin.createFlight(new CreateFlightCommand(route.flightNumber(), route.origin(), route.destination(),
                        departure, departure.plus(route.duration()), "A320", Money.of(route.fare(), "USD"),
                        30, "ABCDEF", 4), "demo-" + route.flightNumber() + "-" + date);
                created++;
            }
        }
        LOG.info("Demo schedule ready: {} flights over the next {} days", created, DAYS);
    }
}
