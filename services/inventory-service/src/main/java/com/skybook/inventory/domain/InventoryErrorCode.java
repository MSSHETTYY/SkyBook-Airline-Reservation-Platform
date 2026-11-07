package com.skybook.inventory.domain;

import org.springframework.http.HttpStatus;

import com.skybook.common.error.ErrorCode;

/** Inventory-specific error codes; part of the public API contract. */
public enum InventoryErrorCode implements ErrorCode {

    SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Seat no longer available"),
    UNKNOWN_SEAT(HttpStatus.UNPROCESSABLE_CONTENT, "Seat does not exist on this flight"),
    FLIGHT_NOT_BOOKABLE(HttpStatus.UNPROCESSABLE_CONTENT, "Flight is not open for booking"),
    HOLD_EXPIRED(HttpStatus.CONFLICT, "Seat hold expired and its seats were taken"),
    HOLD_NOT_ACTIVE(HttpStatus.CONFLICT, "Seat hold is no longer active"),
    DUPLICATE_FLIGHT(HttpStatus.CONFLICT, "A flight with this number and departure time already exists"),
    INVALID_FLIGHT(HttpStatus.UNPROCESSABLE_CONTENT, "Flight data is inconsistent");

    private final HttpStatus httpStatus;
    private final String title;

    InventoryErrorCode(HttpStatus httpStatus, String title) {
        this.httpStatus = httpStatus;
        this.title = title;
    }

    @Override
    public String code() {
        return name();
    }

    @Override
    public int status() {
        return httpStatus.value();
    }

    @Override
    public String title() {
        return title;
    }
}
