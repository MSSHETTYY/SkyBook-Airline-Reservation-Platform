package com.skybook.booking.adapter.out.persistence;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "booking_pending_change")
class PendingChangeEntity {

    @Id
    @Column(name = "booking_id")
    private Long bookingId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "booking_id")
    private BookingEntity booking;

    @Column(name = "new_flight_id", nullable = false)
    private Long newFlightId;

    @Column(name = "new_flight_number", nullable = false)
    private String newFlightNumber;

    @Column(name = "new_origin", nullable = false)
    private String newOrigin;

    @Column(name = "new_destination", nullable = false)
    private String newDestination;

    @Column(name = "new_departure_time", nullable = false)
    private Instant newDepartureTime;

    @Column(name = "hold_id", nullable = false)
    private String holdId;

    @Column(name = "hold_expires_at", nullable = false)
    private Instant holdExpiresAt;

    /** "12A:110.00,12B:110.00" - one entry per passenger, in passenger order. */
    @Column(name = "new_seats", nullable = false)
    private String newSeats;

    @Column(name = "amount_due", nullable = false)
    private BigDecimal amountDue;

    @Column(name = "payment_id")
    private String paymentId;

    @Column(nullable = false)
    private boolean paid;

    protected PendingChangeEntity() {
        // for JPA
    }

    PendingChangeEntity(BookingEntity booking) {
        this.booking = booking;
    }

    Long getNewFlightId() {
        return newFlightId;
    }

    String getNewFlightNumber() {
        return newFlightNumber;
    }

    String getNewOrigin() {
        return newOrigin;
    }

    String getNewDestination() {
        return newDestination;
    }

    Instant getNewDepartureTime() {
        return newDepartureTime;
    }

    String getHoldId() {
        return holdId;
    }

    Instant getHoldExpiresAt() {
        return holdExpiresAt;
    }

    String getNewSeats() {
        return newSeats;
    }

    BigDecimal getAmountDue() {
        return amountDue;
    }

    String getPaymentId() {
        return paymentId;
    }

    boolean isPaid() {
        return paid;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    void update(Long flightId, String flightNumber, String origin, String destination, Instant departure,
                String newHoldId, Instant newHoldExpiresAt, String seats, BigDecimal due, String newPaymentId,
                boolean isPaid) {
        this.newFlightId = flightId;
        this.newFlightNumber = flightNumber;
        this.newOrigin = origin;
        this.newDestination = destination;
        this.newDepartureTime = departure;
        this.holdId = newHoldId;
        this.holdExpiresAt = newHoldExpiresAt;
        this.newSeats = seats;
        this.amountDue = due;
        this.paymentId = newPaymentId;
        this.paid = isPaid;
    }
}
