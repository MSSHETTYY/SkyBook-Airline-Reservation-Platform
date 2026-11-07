package com.skybook.booking.domain.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

import com.skybook.common.money.Money;

/**
 * A date change that is waiting for the customer to pay the fare difference.
 *
 * @param newFlight      the flight being changed to
 * @param holdId         hold on the new seats (expires like any hold)
 * @param holdExpiresAt  payment deadline for the difference
 * @param newSeats       one entry per passenger, in passenger order
 * @param amountDue      fare difference plus change fee
 * @param paymentId      payment collecting {@code amountDue}
 */
public record PendingChange(FlightSnapshot newFlight, String holdId, Instant holdExpiresAt,
                            List<SeatAssignment> newSeats, Money amountDue, String paymentId) {

    /** New seat and its price for one passenger. */
    public record SeatAssignment(String seatNumber, Money price) {
    }

    public PendingChange {
        Objects.requireNonNull(newFlight, "newFlight");
        Objects.requireNonNull(holdId, "holdId");
        Objects.requireNonNull(holdExpiresAt, "holdExpiresAt");
        newSeats = List.copyOf(newSeats);
        Objects.requireNonNull(amountDue, "amountDue");
    }

    public PendingChange withPayment(String id) {
        return new PendingChange(newFlight, holdId, holdExpiresAt, newSeats, amountDue, id);
    }
}
