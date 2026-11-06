package com.skybook.common.event;

/**
 * Canonical names of SkyBook domain events. Names are past-tense facts and part of the public
 * messaging contract: renaming one is a breaking change for every consumer.
 */
public final class EventTypes {

    public static final String BOOKING_CREATED = "BookingCreated";
    public static final String SEATS_HELD = "SeatsHeld";
    public static final String PAYMENT_COMPLETED = "PaymentCompleted";
    public static final String PAYMENT_FAILED = "PaymentFailed";
    public static final String TICKET_ISSUED = "TicketIssued";
    public static final String BOOKING_CANCELLED = "BookingCancelled";
    public static final String REFUND_ISSUED = "RefundIssued";

    private EventTypes() {
    }
}
