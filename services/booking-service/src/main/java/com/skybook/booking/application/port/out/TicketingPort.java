package com.skybook.booking.application.port.out;

import java.util.List;

import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;

/**
 * Driven port: the GDS that issues e-tickets. Issuing is idempotent per booking reference and flight
 * (the GDS returns the same tickets for a repeated request).
 */
public interface TicketingPort {

    /** Issues one ticket per passenger, in passenger order. */
    List<String> issueTickets(String bookingReference, FlightSnapshot flight, List<Passenger> passengers);

    /** Voids tickets (cancellation, date change); idempotent. */
    void voidTickets(String bookingReference, List<String> ticketNumbers);
}
