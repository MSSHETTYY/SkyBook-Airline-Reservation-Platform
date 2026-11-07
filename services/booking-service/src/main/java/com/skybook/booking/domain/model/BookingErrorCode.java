package com.skybook.booking.domain.model;

import com.skybook.common.error.ErrorCode;

/** Booking-specific error codes; part of the public API contract. Framework-free (plain status codes). */
public enum BookingErrorCode implements ErrorCode {

    SEAT_UNAVAILABLE(409, "Seat no longer available"),
    FLIGHT_NOT_BOOKABLE(422, "Flight is not open for booking"),
    UNKNOWN_SEAT(422, "Seat does not exist on this flight"),
    DUPLICATE_SEAT(422, "Each passenger needs a different seat"),
    INVALID_STATE(409, "The booking is not in a state that allows this action"),
    NOT_CANCELLABLE(422, "The booking can no longer be cancelled"),
    NOT_CHANGEABLE(422, "The booking can no longer be changed"),
    HOLD_EXPIRED(409, "The seat hold expired before the booking could be completed"),
    REFUND_EXCEEDS_PAYMENT(422, "Refund exceeds the amount paid");

    private final int status;
    private final String title;

    BookingErrorCode(int status, String title) {
        this.status = status;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String title() {
        return title;
    }
}
