package com.skybook.inventory.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.InventoryErrorCode;
import com.skybook.inventory.domain.SeatLayoutGenerator;
import com.skybook.inventory.repository.FlightRepository;
import com.skybook.inventory.repository.SeatRepository;

/** Flight and inventory management for agents and admins. */
@Service
public class FlightAdminService {

    private static final Logger LOG = LoggerFactory.getLogger(FlightAdminService.class);

    private final FlightRepository flights;
    private final SeatRepository seats;
    private final FlightQueryService queries;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public FlightAdminService(FlightRepository flights, SeatRepository seats, FlightQueryService queries,
                              TransactionTemplate tx, ApplicationEventPublisher events, Clock clock) {
        this.flights = flights;
        this.seats = seats;
        this.queries = queries;
        this.tx = tx;
        this.events = events;
        this.clock = clock;
    }

    /** Input for {@link #createFlight}. */
    public record CreateFlightCommand(String flightNumber, String origin, String destination, Instant departureTime,
                                      Instant arrivalTime, String aircraftType, Money baseFare, int rows,
                                      String seatLetters, int businessRows) {
    }

    /** Creates a flight and its seats atomically. Idempotent per {@code idempotencyKey}. */
    public FlightView createFlight(CreateFlightCommand command, String idempotencyKey) {
        Long flightId;
        try {
            flightId = tx.execute(status -> flights.findByCreationKey(idempotencyKey)
                    .map(existing -> requireSameFlight(existing, command).getId())
                    .orElseGet(() -> insert(command, idempotencyKey)));
        } catch (DataIntegrityViolationException ex) {
            flightId = tx.execute(status -> flights.findByCreationKey(idempotencyKey)
                    .map(existing -> requireSameFlight(existing, command).getId())
                    .orElseThrow(() -> new SkyBookException(InventoryErrorCode.DUPLICATE_FLIGHT,
                            "Flight %s departing %s already exists"
                                    .formatted(command.flightNumber(), command.departureTime()))));
        }
        return queries.flight(flightId);
    }

    public FlightView cancelFlight(Long flightId) {
        tx.executeWithoutResult(status -> {
            Flight flight = flights.findById(flightId)
                    .orElseThrow(() -> new ResourceNotFoundException("Flight", flightId));
            flight.cancel();
            events.publishEvent(new SeatInventoryChanged(flightId));
            LOG.info("Flight {} ({}) cancelled", flight.getFlightNumber(), flightId);
        });
        return queries.flight(flightId);
    }

    public Page<FlightView> listFlights(int page, int size) {
        return tx.execute(status -> flights.findAllByOrderByDepartureTimeDesc(PageRequest.of(page, size))
                .map(flight -> queries.flight(flight.getId())));
    }

    private Long insert(CreateFlightCommand c, String idempotencyKey) {
        if (!c.departureTime().isAfter(clock.instant())) {
            throw new SkyBookException(InventoryErrorCode.INVALID_FLIGHT, "Departure must be in the future");
        }
        if (flights.existsByFlightNumberAndDepartureTime(c.flightNumber(), c.departureTime())) {
            throw new SkyBookException(InventoryErrorCode.DUPLICATE_FLIGHT,
                    "Flight %s departing %s already exists".formatted(c.flightNumber(), c.departureTime()));
        }
        Flight flight = flights.save(new Flight(c.flightNumber(), c.origin(), c.destination(), c.departureTime(),
                c.arrivalTime(), c.aircraftType(), c.baseFare(), idempotencyKey, clock.instant()));
        seats.saveAll(SeatLayoutGenerator.generate(flight.getId(), c.rows(), c.seatLetters(), c.businessRows()));
        LOG.info("Flight {} departing {} created with {} seats", c.flightNumber(), c.departureTime(),
                c.rows() * c.seatLetters().length());
        return flight.getId();
    }

    private static Flight requireSameFlight(Flight existing, CreateFlightCommand command) {
        if (!Objects.equals(existing.getFlightNumber(), command.flightNumber())
                || !Objects.equals(existing.getDepartureTime(), command.departureTime())) {
            throw new SkyBookException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used to create a different flight");
        }
        return existing;
    }
}
