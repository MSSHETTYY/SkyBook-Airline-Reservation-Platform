package com.skybook.inventory.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.skybook.common.error.CommonErrorCode;
import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;
import com.skybook.inventory.config.InventoryProperties;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.InventoryErrorCode;
import com.skybook.inventory.domain.PricingPolicy;
import com.skybook.inventory.domain.Seat;
import com.skybook.inventory.domain.SeatHold;
import com.skybook.inventory.domain.SeatStatus;
import com.skybook.inventory.repository.FlightRepository;
import com.skybook.inventory.repository.SeatHoldRepository;
import com.skybook.inventory.repository.SeatRepository;

/**
 * Command side of seat inventory: hold, confirm and release.
 *
 * <p><b>Why overbooking is impossible:</b> a seat changes state only through an {@code UPDATE ... WHERE
 * id = ? AND version = ?}. When two transactions try to take the same seat, the database serialises
 * the row updates; the second one finds the version already incremented, updates zero rows, and JPA
 * raises an optimistic-locking failure, which is reported as {@code SEAT_UNAVAILABLE}. No reads from a
 * cache or from a stale snapshot are ever used to decide allocation. Updates are flushed in primary-key
 * order ({@code hibernate.order_updates}) so concurrent multi-seat holds cannot deadlock each other.
 *
 * <p><b>Idempotency:</b> the {@code Idempotency-Key} is stored on the hold in the same transaction (unique
 * constraint). A retry with the same key and payload replays the original hold; a different payload is
 * rejected; two concurrent requests with the same key race on the constraint and the loser replays.
 */
@Service
public class SeatHoldService {

    private static final Logger LOG = LoggerFactory.getLogger(SeatHoldService.class);
    private static final int MAX_CONFIRM_ATTEMPTS = 3;

    private final FlightRepository flights;
    private final SeatRepository seats;
    private final SeatHoldRepository holds;
    private final PricingPolicy pricing;
    private final TransactionTemplate tx;
    private final ApplicationEventPublisher events;
    private final InventoryProperties properties;
    private final Clock clock;

    public SeatHoldService(FlightRepository flights, SeatRepository seats, SeatHoldRepository holds,
                           PricingPolicy pricing, TransactionTemplate tx, ApplicationEventPublisher events,
                           InventoryProperties properties, Clock clock) {
        this.flights = flights;
        this.seats = seats;
        this.holds = holds;
        this.pricing = pricing;
        this.tx = tx;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    public SeatHoldView holdSeats(Long flightId, Collection<String> seatNumbers, String idempotencyKey) {
        Set<String> requested = new TreeSet<>(seatNumbers);
        String fingerprint = flightId + ":" + String.join(",", requested);
        try {
            return tx.execute(status -> holds.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> replay(existing, fingerprint))
                    .orElseGet(() -> placeHold(flightId, requested, idempotencyKey, fingerprint)));
        } catch (DataIntegrityViolationException ex) {
            // A concurrent request with the same key committed first; replay its result.
            return tx.execute(status -> holds.findByIdempotencyKey(idempotencyKey)
                    .map(existing -> replay(existing, fingerprint))
                    .orElseThrow(() -> ex));
        }
    }

    public SeatHoldView hold(String holdId) {
        return tx.execute(status -> {
            SeatHold hold = findHold(holdId);
            return SeatHoldView.of(hold, findFlight(hold.getFlightId()));
        });
    }

    /**
     * Books the held seats. Idempotent for the same booking reference.
     *
     * <p>A confirmation can collide with the expiry sweeper touching the same hold. That collision is
     * retried: the retry re-reads the current state and still succeeds if no other hold took the seats.
     */
    public SeatHoldView confirm(String holdId, String bookingReference) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> {
                    SeatHold hold = findHold(holdId);
                    if (hold.confirm(bookingReference, seatsOf(hold), clock.instant())) {
                        holds.flush();
                        events.publishEvent(new SeatInventoryChanged(hold.getFlightId()));
                        LOG.info("Hold {} confirmed for booking {}", holdId, bookingReference);
                    }
                    return SeatHoldView.of(hold, findFlight(hold.getFlightId()));
                });
            } catch (ConcurrencyFailureException ex) {
                if (attempt >= MAX_CONFIRM_ATTEMPTS) {
                    throw ex;
                }
                LOG.info("Concurrent change while confirming hold {}; retrying (attempt {})", holdId, attempt);
            }
        }
    }

    /** Releases an active hold. Idempotent; releasing an expired or released hold is a no-op. */
    public void release(String holdId) {
        tx.executeWithoutResult(status -> {
            SeatHold hold = findHold(holdId);
            if (hold.release(seatsOf(hold))) {
                flushSeatChanges(hold.seatNumbers());
                events.publishEvent(new SeatInventoryChanged(hold.getFlightId()));
                LOG.info("Hold {} released", holdId);
            }
        });
    }

    /** Returns all seats booked under {@code bookingReference} on a flight to sale. Idempotent. */
    public List<String> releaseBookedSeats(Long flightId, String bookingReference) {
        return tx.execute(status -> {
            findFlight(flightId);
            List<String> released = seats.findByFlightIdAndBookingReferenceAndStatus(
                            flightId, bookingReference, SeatStatus.BOOKED).stream()
                    .filter(seat -> seat.releaseBooking(bookingReference))
                    .map(Seat::getSeatNumber)
                    .sorted()
                    .toList();
            if (!released.isEmpty()) {
                flushSeatChanges(released);
                events.publishEvent(new SeatInventoryChanged(flightId));
                LOG.info("Released seats {} of booking {} on flight {}", released, bookingReference, flightId);
            }
            return released;
        });
    }

    private SeatHoldView placeHold(Long flightId, Set<String> requested, String idempotencyKey, String fingerprint) {
        Flight flight = findFlight(flightId);
        List<Seat> selected = seats.findByFlightIdAndSeatNumberIn(flightId, requested);
        if (selected.size() != requested.size()) {
            Set<String> found = selected.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());
            List<String> unknown = requested.stream().filter(n -> !found.contains(n)).toList();
            throw new SkyBookException(InventoryErrorCode.UNKNOWN_SEAT,
                    "Unknown seats on flight %s: %s".formatted(flight.getFlightNumber(), String.join(", ", unknown)),
                    Map.of("seatNumbers", unknown), null);
        }

        Instant now = clock.instant();
        List<Seat> allSeats = seats.findByFlightIdOrderById(flightId);
        int occupied = (int) allSeats.stream().filter(seat -> seat.isOccupiedAt(now)).count();
        Money baseFare = flight.baseFare();
        Function<Seat, Money> priceOf =
                seat -> pricing.priceFor(seat.getCabinClass(), baseFare, occupied, allSeats.size());

        SeatHold hold = SeatHold.place(flight, selected, priceOf, now, properties.holdTtl(), idempotencyKey,
                fingerprint);
        holds.save(hold);
        flushSeatChanges(requested);
        events.publishEvent(new SeatInventoryChanged(flightId));
        LOG.info("Hold {} placed on flight {} for seats {}", hold.getId(), flight.getFlightNumber(), requested);
        return SeatHoldView.of(hold, flight);
    }

    private SeatHoldView replay(SeatHold existing, String fingerprint) {
        if (!existing.matchesRequest(fingerprint)) {
            throw new SkyBookException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used for a different seat selection");
        }
        return SeatHoldView.of(existing, findFlight(existing.getFlightId()));
    }

    /**
     * Forces the version-checked UPDATEs now, inside the transaction, so a lost race surfaces here as a
     * meaningful domain error instead of an anonymous failure at commit time.
     */
    private void flushSeatChanges(Collection<String> seatNumbers) {
        try {
            seats.flush();
        } catch (ConcurrencyFailureException ex) {
            LOG.info("Lost concurrent race for seats {}: {}", seatNumbers, ex.getClass().getSimpleName());
            throw SeatHold.lostRace(seatNumbers);
        }
    }

    private List<Seat> seatsOf(SeatHold hold) {
        return seats.findByFlightIdAndSeatNumberIn(hold.getFlightId(), hold.seatNumbers());
    }

    private SeatHold findHold(String holdId) {
        return holds.findById(holdId).orElseThrow(() -> new ResourceNotFoundException("Hold", holdId));
    }

    private Flight findFlight(Long flightId) {
        return flights.findById(flightId).orElseThrow(() -> new ResourceNotFoundException("Flight", flightId));
    }
}
