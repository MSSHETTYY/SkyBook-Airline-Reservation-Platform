package com.skybook.booking.application.port.out;

import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.common.money.Money;

/**
 * Driven port: booking domain events. Must be called inside the transaction that saves the booking, so the
 * state change and its event commit atomically (transactional outbox).
 */
public interface BookingEventPublisher {

    void bookingCreated(Booking booking);

    void seatsHeld(Booking booking);

    void ticketIssued(Booking booking);

    void bookingChanged(Booking booking, FlightSnapshot previousFlight);

    void bookingCancelled(Booking booking, String reason, Money refundAmount);
}
