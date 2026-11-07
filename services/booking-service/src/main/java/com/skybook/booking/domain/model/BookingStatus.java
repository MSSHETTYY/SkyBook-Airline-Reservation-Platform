package com.skybook.booking.domain.model;

import java.util.EnumSet;
import java.util.Set;

/**
 * Booking lifecycle. Allowed transitions are declared here once and enforced by {@link Booking}.
 *
 * <pre>
 * PENDING_PAYMENT --pay--> PAID --ticket--> CONFIRMED --cancel--> CANCELLATION_PENDING --refund--> CANCELLED
 *        |                   |                  |
 *     expire               fail            change date (fare difference due)
 *        v                   v                  v
 *     EXPIRED              FAILED       CHANGE_PENDING_PAYMENT --pay--> CONFIRMED
 * </pre>
 */
public enum BookingStatus {
    PENDING_PAYMENT,
    PAID,
    CONFIRMED,
    CHANGE_PENDING_PAYMENT,
    CANCELLATION_PENDING,
    CANCELLED,
    EXPIRED,
    FAILED;

    public Set<BookingStatus> allowedNext() {
        return switch (this) {
            case PENDING_PAYMENT -> EnumSet.of(PAID, EXPIRED, CANCELLED);
            case PAID -> EnumSet.of(CONFIRMED, FAILED);
            case CONFIRMED -> EnumSet.of(CHANGE_PENDING_PAYMENT, CANCELLATION_PENDING, CONFIRMED);
            case CHANGE_PENDING_PAYMENT -> EnumSet.of(CONFIRMED);
            case CANCELLATION_PENDING -> EnumSet.of(CANCELLED);
            case CANCELLED, EXPIRED, FAILED -> EnumSet.noneOf(BookingStatus.class);
        };
    }

    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }
}
