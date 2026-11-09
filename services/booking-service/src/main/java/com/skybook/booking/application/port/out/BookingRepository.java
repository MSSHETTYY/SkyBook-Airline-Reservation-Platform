package com.skybook.booking.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.skybook.booking.application.port.in.PageResult;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.BookingStatus;

/** Driven port: booking persistence. Saves use optimistic locking on the booking version. */
public interface BookingRepository {

    /**
     * Inserts or updates the booking and returns the saved state (with id and new version).
     *
     * @throws DuplicateBookingException if the reference or the customer's idempotency key already exists
     */
    Booking save(Booking booking);

    Optional<Booking> findByReference(String reference);

    Optional<Booking> findByCustomerAndIdempotencyKey(String customerId, String idempotencyKey);

    PageResult<Booking> findByCustomer(String customerId, int page, int size);

    PageResult<Booking> search(BookingStatus status, Long flightId, int page, int size);

    /** References of bookings in {@code status} whose seat hold expired at or before {@code now}. */
    List<String> findWithHoldExpiredBefore(BookingStatus status, Instant now, int limit);

    /** References of bookings in {@code status} whose last change was at or before {@code updatedBefore}. */
    List<String> findNotUpdatedSince(BookingStatus status, Instant updatedBefore, int limit);

    /** Thrown when a unique constraint (reference or idempotency key) is violated. */
    final class DuplicateBookingException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final boolean duplicateIdempotencyKey;

        public DuplicateBookingException(boolean duplicateIdempotencyKey, Throwable cause) {
            super(duplicateIdempotencyKey ? "Duplicate idempotency key" : "Duplicate booking reference", cause);
            this.duplicateIdempotencyKey = duplicateIdempotencyKey;
        }

        public boolean isDuplicateIdempotencyKey() {
            return duplicateIdempotencyKey;
        }
    }
}
