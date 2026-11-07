package com.skybook.inventory.domain;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One physical seat on one flight, and the unit of concurrency control.
 *
 * <p>State machine: {@code AVAILABLE -> HELD -> BOOKED}, back to {@code AVAILABLE} on release. A hold
 * whose {@code heldUntil} has passed counts as available even before the expiry sweeper resets it,
 * so expired holds never block sales. Every transition bumps {@link #version}; JPA turns concurrent
 * transitions of the same seat into an optimistic-locking failure for all but one transaction.
 */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "flight_id", nullable = false)
    private Long flightId;

    @Column(name = "seat_number", nullable = false)
    private String seatNumber;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "cabin_class", nullable = false)
    private CabinClass cabinClass;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(nullable = false)
    private SeatStatus status;

    @Column(name = "hold_id")
    private String holdId;

    @Column(name = "held_until")
    private Instant heldUntil;

    @Column(name = "booking_reference")
    private String bookingReference;

    @Version
    private long version;

    protected Seat() {
        // for JPA
    }

    public Seat(Long flightId, String seatNumber, CabinClass cabinClass) {
        this.flightId = Objects.requireNonNull(flightId);
        this.seatNumber = Objects.requireNonNull(seatNumber);
        this.cabinClass = Objects.requireNonNull(cabinClass);
        this.status = SeatStatus.AVAILABLE;
    }

    /** Whether a new hold may take this seat at {@code now}. */
    public boolean isAvailableAt(Instant now) {
        return status == SeatStatus.AVAILABLE || (status == SeatStatus.HELD && !heldUntil.isAfter(now));
    }

    /** Whether the seat counts towards the flight's load factor (sold or validly held). */
    public boolean isOccupiedAt(Instant now) {
        return !isAvailableAt(now);
    }

    public boolean isHeldBy(String candidateHoldId) {
        return status == SeatStatus.HELD && Objects.equals(holdId, candidateHoldId);
    }

    void hold(String newHoldId, Instant until, Instant now) {
        if (!isAvailableAt(now)) {
            throw new IllegalStateException("Seat " + seatNumber + " is not available");
        }
        this.status = SeatStatus.HELD;
        this.holdId = newHoldId;
        this.heldUntil = until;
        this.bookingReference = null;
    }

    /**
     * Books the seat for a confirmed hold. Allowed while the seat is still held by that hold, or if the
     * hold lapsed but nobody else took the seat in the meantime (late payment is honoured when possible).
     */
    void book(String confirmingHoldId, String reference, Instant now) {
        if (!isHeldBy(confirmingHoldId) && !isAvailableAt(now)) {
            throw new IllegalStateException("Seat " + seatNumber + " was taken by another hold");
        }
        this.status = SeatStatus.BOOKED;
        this.holdId = confirmingHoldId;
        this.heldUntil = null;
        this.bookingReference = reference;
    }

    void release() {
        this.status = SeatStatus.AVAILABLE;
        this.holdId = null;
        this.heldUntil = null;
        this.bookingReference = null;
    }

    /** Returns a booked seat to sale if it belongs to {@code reference}; idempotent. */
    public boolean releaseBooking(String reference) {
        if (status == SeatStatus.BOOKED && Objects.equals(bookingReference, reference)) {
            release();
            return true;
        }
        return false;
    }

    public Long getId() {
        return id;
    }

    public Long getFlightId() {
        return flightId;
    }

    public String getSeatNumber() {
        return seatNumber;
    }

    public CabinClass getCabinClass() {
        return cabinClass;
    }

    public SeatStatus getStatus() {
        return status;
    }

    public String getHoldId() {
        return holdId;
    }

    public String getBookingReference() {
        return bookingReference;
    }

    public long getVersion() {
        return version;
    }
}
