package com.skybook.booking.adapter.out.partner;

import java.time.Instant;
import java.util.List;

import org.springframework.web.client.RestClient;

import com.skybook.booking.adapter.out.support.RemoteCall;
import com.skybook.booking.application.port.out.TicketingPort;
import com.skybook.booking.domain.model.FlightSnapshot;
import com.skybook.booking.domain.model.Passenger;
import com.skybook.common.web.CorrelationId;

/**
 * {@link TicketingPort} for the GDS (simulated by WireMock in mock-partners). Ticket issuing carries an
 * idempotency key per booking and flight, so a saga retry after a timeout gets the same tickets back.
 */
public class GdsTicketingAdapter implements TicketingPort {

    record TicketPassenger(String firstName, String lastName, String seatNumber) {
    }

    record IssueRequest(String bookingReference, String flightNumber, Instant departureTime,
                        List<TicketPassenger> passengers) {
    }

    record IssueResponse(List<String> ticketNumbers) {
    }

    record VoidRequest(String bookingReference, List<String> ticketNumbers) {
    }

    private final RestClient restClient;
    private final RemoteCall remote;

    public GdsTicketingAdapter(RestClient restClient, RemoteCall remote) {
        this.restClient = restClient;
        this.remote = remote;
    }

    @Override
    public List<String> issueTickets(String bookingReference, FlightSnapshot flight, List<Passenger> passengers) {
        IssueRequest request = new IssueRequest(bookingReference, flight.flightNumber(), flight.departureTime(),
                passengers.stream().map(p -> new TicketPassenger(p.firstName(), p.lastName(), p.seatNumber()))
                        .toList());
        IssueResponse response = remote.execute("issueTickets", () -> restClient.post()
                .uri("/gds/v1/tickets")
                .header("Idempotency-Key", bookingReference + "-" + flight.flightId())
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(request)
                .retrieve()
                .onStatus(status -> status.isError(), remote::raise)
                .body(IssueResponse.class));
        if (response == null || response.ticketNumbers() == null
                || response.ticketNumbers().size() != passengers.size()) {
            throw new IllegalStateException("GDS returned an unexpected number of tickets for " + bookingReference);
        }
        return response.ticketNumbers();
    }

    @Override
    public void voidTickets(String bookingReference, List<String> ticketNumbers) {
        if (ticketNumbers.isEmpty()) {
            return;
        }
        remote.execute("voidTickets", () -> restClient.post()
                .uri("/gds/v1/tickets/void")
                .header(CorrelationId.HEADER, CorrelationId.current().orElse(null))
                .body(new VoidRequest(bookingReference, ticketNumbers))
                .retrieve()
                .onStatus(status -> status.isError(), remote::raise)
                .toBodilessEntity());
    }
}
