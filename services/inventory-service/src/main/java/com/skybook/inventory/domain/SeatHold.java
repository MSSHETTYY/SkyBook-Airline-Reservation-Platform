package com.skybook.inventory.domain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.skybook.common.error.SkyBookException;
import com.skybook.common.money.Money;

/**
 * A time-limited reservation of seats with locked-in prices.
 *
 * <p>State machine: {@code ACTIVE -> CONFIRMED | RELEASED | EXPIRED}. All seat state changes go through
 * this aggregate so the hold and its seats always change together in one transaction.
 */
@Entity
@Table(name = "seat_hold")
public class SeatHold {

    @Id
    private String id;

    @Column(name = "flight_id", nullable = false)
    private Long flightId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private HoldStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(nullable = false)
    private String currency;

    @Column(name = "idempotency_key", nullable = false)
    private String idempotencyKey;

    @Column(name = "request_fingerprint", nullable = false)
    private String requestFingerprint;

    @Column(name = "booking_reference")
    private String bookingReference;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "seat_hold_line", joinColumns = @JoinColumn(name = "hold_id"))
    @OrderBy("seatNumber")
    private List<HeldSeatLine> lines = new ArrayList<>();

    @Version
    private long version;

    protected SeatHold() {
        // for JPA
    }

    /**
     * Places a hold on {@code seats}.
     *
     * @param priceOf price of each seat, quoted before the hold is placed
     * @throws SkyBookException {@code SEAT_UNAVAILABLE} listing every seat that is already taken
     */
    public static SeatHold place(Flight flight, Collection<Seat> seats, Function<Seat, Money> priceOf, Instant now,
                                 Duration ttl, String idempotencyKey, String requestFingerprint) {
        flight.requireBookable(now);
        List<String> unavailable = seats.stream()
                .filter(seat -> !seat.isAvailableAt(now))
                .map(Seat::getSeatNumber)
                .sorted()
                .toList();
        if (!unavailable.isEmpty()) {
            throw seatsUnavailable(unavailable);
        }

        SeatHold hold = new SeatHold();
        hold.id = UUID.randomUUID().toString();
        hold.flightId = flight.getId();
        hold.status = HoldStatus.ACTIVE;
        hold.createdAt = now;
        hold.expiresAt = now.plus(ttl);
        hold.idempotencyKey = idempotencyKey;
        hold.requestFingerprint = requestFingerprint;

        Money total = Money.zero(flight.baseFare().currency());
        for (Seat seat : seats.stream().sorted(Comparator.comparing(Seat::getSeatNumber)).toList()) {
            Money price = priceOf.apply(seat);
            seat.hold(hold.id, hold.expiresAt, now);
            hold.lines.add(new HeldSeatLine(seat.getSeatNumber(), seat.getCabinClass(), price.amount()));
            total = total.plus(price);
        }
        hold.totalAmount = total.amount();
        hold.currency = total.currencyCode();
        return hold;
    }

    /**
     * Books the held seats under {@code reference}. Idempotent for the same reference.
     *
     * @param seats the seats listed in this hold's lines
     * @return {@code true} if seats changed state, {@code false} if the hold was already confirmed
     */
    public boolean confirm(String reference, Collection<Seat> seats, Instant now) {
        if (status == HoldStatus.CONFIRMED) {
            if (Objects.equals(bookingReference, reference)) {
                return false;
            }
            throw new SkyBookException(InventoryErrorCode.HOLD_NOT_ACTIVE,
                    "Hold %s is already confirmed for another booking".formatted(id));
        }
        if (status == HoldStatus.RELEASED) {
            throw new SkyBookException(InventoryErrorCode.HOLD_NOT_ACTIVE, "Hold %s was released".formatted(id));
        }
        List<String> taken = seats.stream()
                .filter(seat -> !seat.isHeldBy(id) && !seat.isAvailableAt(now))
                .map(Seat::getSeatNumber)
                .sorted()
                .toList();
        if (!taken.isEmpty()) {
            throw new SkyBookException(InventoryErrorCode.HOLD_EXPIRED,
                    "Hold %s expired and seats %s were taken".formatted(id, String.join(", ", taken)),
                    Map.of("seatNumbers", taken), null);
        }
        seats.forEach(seat -> seat.book(id, reference, now));
        status = HoldStatus.CONFIRMED;
        bookingReference = reference;
        return true;
    }

    /** Releases an active hold; a no-op if it already expired or was released. */
    public boolean release(Collection<Seat> seats) {
        if (status == HoldStatus.CONFIRMED) {
            throw new SkyBookException(InventoryErrorCode.HOLD_NOT_ACTIVE,
                    "Hold %s is confirmed; release the booked seats instead".formatted(id));
        }
        if (status != HoldStatus.ACTIVE) {
            return false;
        }
        releaseSeatsHeldByThis(seats);
        status = HoldStatus.RELEASED;
        return true;
    }

    /** Expires the hold if its deadline has passed; returns whether anything changed. */
    public boolean expireIfDue(Collection<Seat> seats, Instant now) {
        if (status != HoldStatus.ACTIVE || expiresAt.isAfter(now)) {
            return false;
        }
        releaseSeatsHeldByThis(seats);
        status = HoldStatus.EXPIRED;
        return true;
    }

    public List<String> seatNumbers() {
        return lines.stream().map(HeldSeatLine::getSeatNumber).toList();
    }

    public Money total() {
        return Money.of(totalAmount, currency);
    }

    public boolean matchesRequest(String fingerprint) {
        return requestFingerprint.equals(fingerprint);
    }

    private void releaseSeatsHeldByThis(Collection<Seat> seats) {
        seats.stream().filter(seat -> seat.isHeldBy(id)).forEach(Seat::release);
    }

    private static SkyBookException seatsUnavailable(List<String> seatNumbers) {
        return new SkyBookException(InventoryErrorCode.SEAT_UNAVAILABLE,
                "Seats no longer available: " + String.join(", ", seatNumbers),
                Map.of("seatNumbers", seatNumbers), null);
    }

    /** Raised by the service when a concurrent transaction won the race for one of these seats. */
    public static SkyBookException lostRace(Collection<String> seatNumbers) {
        return seatsUnavailable(seatNumbers.stream().sorted().toList());
    }

    public String getId() {
        return id;
    }

    public Long getFlightId() {
        return flightId;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public List<HeldSeatLine> getLines() {
        return List.copyOf(lines);
    }
}
