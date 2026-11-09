package com.skybook.booking.adapter.out.messaging;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Component;

import com.skybook.booking.application.port.out.BookingEventPublisher;
import com.skybook.booking.domain.model.Booking;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.common.event.EventEnvelope;
import com.skybook.common.event.EventTypes;
import com.skybook.common.money.Money;
import com.skybook.common.web.CorrelationId;
import com.skybook.messaging.OutboxWriter;

/**
 * Booking events via the transactional outbox, keyed by booking reference (one partition per booking, so
 * consumers see a booking's events in order).
 *
 * <p>Minimal-PII payloads: only the contact e-mail (needed by notification-service to send confirmations)
 * travels on the bus; passenger names and dates of birth never do.
 */
@Component
public class OutboxBookingEventPublisher implements BookingEventPublisher {

    public static final String TOPIC = "skybook.booking.events";
    static final String PRODUCER = "booking-service";

    public record BookingCreated(String bookingReference, String contactEmail, String flightNumber, String origin,
                                 String destination, Instant departureTime, int passengers, BigDecimal totalAmount,
                                 String currency, Instant paymentDueBy) {
    }

    public record SeatsHeld(String bookingReference, Long flightId, String holdId, List<String> seatNumbers,
                            Instant expiresAt) {
    }

    public record TicketIssued(String bookingReference, String contactEmail, String flightNumber, String origin,
                               String destination, Instant departureTime, List<String> ticketNumbers) {
    }

    public record BookingChanged(String bookingReference, String contactEmail, String previousFlightNumber,
                                 String flightNumber, Instant departureTime, List<String> ticketNumbers) {
    }

    public record BookingCancelled(String bookingReference, String contactEmail, String reason,
                                   BigDecimal refundAmount, String currency) {
    }

    private final OutboxWriter outbox;
    private final Clock clock;

    public OutboxBookingEventPublisher(OutboxWriter outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void bookingCreated(Booking b) {
        FlightSnapshot f = b.getFlight();
        publish(EventTypes.BOOKING_CREATED, b, new BookingCreated(b.getReference().value(), b.getContactEmail(),
                f.flightNumber(), f.origin(), f.destination(), f.departureTime(), b.getPassengers().size(),
                b.getTotalPrice().amount(), b.getTotalPrice().currencyCode(), b.getHoldExpiresAt()));
    }

    @Override
    public void seatsHeld(Booking b) {
        publish(EventTypes.SEATS_HELD, b, new SeatsHeld(b.getReference().value(), b.getFlight().flightId(),
                b.getHoldId(), b.seatNumbers(), b.getHoldExpiresAt()));
    }

    @Override
    public void ticketIssued(Booking b) {
        FlightSnapshot f = b.getFlight();
        publish(EventTypes.TICKET_ISSUED, b, new TicketIssued(b.getReference().value(), b.getContactEmail(),
                f.flightNumber(), f.origin(), f.destination(), f.departureTime(), b.ticketNumbers()));
    }

    @Override
    public void bookingChanged(Booking b, FlightSnapshot previousFlight) {
        publish(EventTypes.BOOKING_CHANGED, b, new BookingChanged(b.getReference().value(), b.getContactEmail(),
                previousFlight.flightNumber(), b.getFlight().flightNumber(), b.getFlight().departureTime(),
                b.ticketNumbers()));
    }

    @Override
    public void bookingCancelled(Booking b, String reason, Money refundAmount) {
        publish(EventTypes.BOOKING_CANCELLED, b, new BookingCancelled(b.getReference().value(), b.getContactEmail(),
                reason, refundAmount.amount(), refundAmount.currencyCode()));
    }

    private void publish(String type, Booking booking, Object payload) {
        outbox.append(TOPIC, EventEnvelope.builder(type, payload)
                .producer(PRODUCER)
                .aggregate("Booking", booking.getReference().value())
                .correlationId(CorrelationId.current().orElseGet(CorrelationId::generate))
                .build(clock));
    }
}
