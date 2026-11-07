package com.skybook.booking.domain.model;

import java.time.Instant;
import java.util.List;

import com.skybook.common.money.Money;

/**
 * Complete, immutable state of a {@link Booking}, used to persist and rehydrate the aggregate. Keeping
 * persistence on this record (instead of annotating the aggregate) keeps the domain free of JPA.
 */
public record BookingState(
        Long id,
        BookingReference reference,
        String customerId,
        String contactEmail,
        BookingStatus status,
        FlightSnapshot flight,
        List<Passenger> passengers,
        Money totalPrice,
        String holdId,
        Instant holdExpiresAt,
        String paymentId,
        String paymentClientSecret,
        Money amountPaid,
        Money amountRefunded,
        PendingChange pendingChange,
        String idempotencyKey,
        String requestFingerprint,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public BookingState {
        passengers = List.copyOf(passengers);
    }
}
