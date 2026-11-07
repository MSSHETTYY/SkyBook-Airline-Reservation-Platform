package com.skybook.booking.application.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.common.money.Money;

/**
 * Driven port: seat inventory (inventory-service). Implementations translate remote failures into
 * {@link com.skybook.common.error.SkyBookException}s: business rejections keep their inventory error
 * code (e.g. {@code SEAT_UNAVAILABLE}); outages become {@code SERVICE_UNAVAILABLE}.
 */
public interface InventoryPort {

    /** Holds seats for 15 minutes; safe to retry with the same idempotency key. */
    SeatHold holdSeats(Long flightId, Collection<String> seatNumbers, String idempotencyKey);

    /** Books the held seats; idempotent for the same booking reference. */
    void confirmHold(String holdId, String bookingReference);

    /** Releases an unconfirmed hold; idempotent. */
    void releaseHold(String holdId);

    /** Returns booked seats to sale (cancellation, date change); idempotent. */
    void releaseBookedSeats(Long flightId, String bookingReference);

    /** Result of a successful hold. */
    record SeatHold(String holdId, FlightSnapshot flight, List<HeldSeat> seats, Money total, Instant expiresAt) {

        public Money priceOf(String seatNumber) {
            return seats.stream().filter(seat -> seat.seatNumber().equals(seatNumber)).findFirst()
                    .map(HeldSeat::price)
                    .orElseThrow(() -> new IllegalStateException("Seat " + seatNumber + " is not in hold " + holdId));
        }
    }

    record HeldSeat(String seatNumber, Money price) {
    }
}
