package com.skybook.notification.domain;

import java.util.Arrays;
import java.util.Optional;

/**
 * Which domain events produce a customer e-mail, with which subject and template. Events not listed here
 * (SeatsHeld, PaymentCompleted) are internal and are ignored.
 */
public enum NotificationType {

    BOOKING_CREATED("BookingCreated", "Complete your payment for booking %s", "booking-created"),
    TICKET_ISSUED("TicketIssued", "Your e-tickets for booking %s", "ticket-issued"),
    BOOKING_CHANGED("BookingChanged", "Your booking %s has been changed", "booking-changed"),
    BOOKING_CANCELLED("BookingCancelled", "Your booking %s has been cancelled", "booking-cancelled"),
    PAYMENT_FAILED("PaymentFailed", "Payment for booking %s did not go through", "payment-failed"),
    REFUND_ISSUED("RefundIssued", "Refund issued for booking %s", "refund-issued");

    private final String eventType;
    private final String subjectPattern;
    private final String template;

    NotificationType(String eventType, String subjectPattern, String template) {
        this.eventType = eventType;
        this.subjectPattern = subjectPattern;
        this.template = template;
    }

    public static Optional<NotificationType> forEvent(String eventType) {
        return Arrays.stream(values()).filter(type -> type.eventType.equals(eventType)).findFirst();
    }

    public String subjectFor(String bookingReference) {
        return subjectPattern.formatted(bookingReference);
    }

    /** Thymeleaf template under {@code templates/email/}. */
    public String template() {
        return "email/" + template;
    }
}
