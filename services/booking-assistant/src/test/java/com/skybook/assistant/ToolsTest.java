package com.skybook.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.skybook.assistant.tools.BookingLookupTools;
import com.skybook.assistant.tools.BookingLookupTools.BookingSummary;
import com.skybook.assistant.tools.FlightSearchTools;
import com.skybook.assistant.tools.FlightSearchTools.SearchResult;

/** The tools treat model-supplied arguments as untrusted input and never leak personal data to the model. */
class ToolsTest {

    private static final String OFFERS = """
            {"items": [{"flightId": 7, "flightNumber": "SB101", "origin": "DXB", "destination": "LHR",
              "departureTime": "2026-12-01T08:00:00Z", "arrivalTime": "2026-12-01T15:30:00Z",
              "durationMinutes": 450, "aircraftType": "A320",
              "cabins": [{"cabinClass": "ECONOMY", "seatsAvailable": 12,
                          "fromPrice": {"amount": 420.00, "currency": "USD"}},
                         {"cabinClass": "BUSINESS", "seatsAvailable": 0}]}],
             "page": 0, "size": 5, "totalElements": 1}""";

    private static final String BOOKING = """
            {"bookingReference": "K7QX2M", "status": "CONFIRMED", "contactEmail": "ada@example.test",
             "flight": {"flightId": 7, "flightNumber": "SB101", "origin": "DXB", "destination": "LHR",
                        "departureTime": "2026-12-01T08:00:00Z"},
             "passengers": [{"firstName": "Ada", "lastName": "Lovelace", "seatNumber": "5B",
                             "ticketNumber": "1761234567890"}],
             "totalPrice": {"amount": 420.00, "currency": "USD"}, "amountPaid": {"amount": 420.00, "currency": "USD"},
             "amountRefunded": {"amount": 0, "currency": "USD"}, "createdAt": "2026-11-01T10:00:00Z"}""";

    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://upstream");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

    @Test
    void searchesFlightsAndSummarisesPerCabin() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://upstream/api/v2/flights")))
                .andExpect(queryParam("origin", "DXB"))
                .andExpect(queryParam("destination", "LHR"))
                .andExpect(queryParam("departureFrom", "2026-12-01"))
                .andExpect(queryParam("passengers", "9"))
                .andRespond(withSuccess(OFFERS, MediaType.APPLICATION_JSON));

        SearchResult result = new FlightSearchTools(builder.build()).searchFlights(" dxb", "lhr ", "2026-12-01", 42);

        server.verify();
        assertThat(result.message()).isEqualTo("1 flight(s) found.");
        assertThat(result.flights()).singleElement().satisfies(flight -> {
            assertThat(flight.route()).isEqualTo("DXB-LHR");
            assertThat(flight.duration()).isEqualTo("7h 30m");
            assertThat(flight.economy()).isEqualTo("12 seats from 420.00 USD");
            assertThat(flight.business()).isEqualTo("sold out");
        });
    }

    @Test
    void rejectsBadArgumentsWithoutCallingInventory() {
        FlightSearchTools tools = new FlightSearchTools(builder.build());

        assertThat(tools.searchFlights("Dubai", "LHR", "2026-12-01", 1).message()).contains("3-letter IATA");
        assertThat(tools.searchFlights("DXB", "LHR", "next friday", 1).message()).contains("YYYY-MM-DD");
        assertThat(tools.searchFlights(null, "LHR", null, null).flights()).isEmpty();
        server.verify();
    }

    @Test
    void reportsEmptyResultsAndOutagesAsText() {
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://upstream/api/v2/flights")))
                .andRespond(withSuccess("{\"items\": [], \"totalElements\": 0}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(org.hamcrest.Matchers.startsWith("http://upstream/api/v2/flights")))
                .andRespond(withServerError());
        FlightSearchTools tools = new FlightSearchTools(builder.build());

        assertThat(tools.searchFlights("DXB", "LHR", "2026-12-01", null).message())
                .isEqualTo("No flights from DXB to LHR on 2026-12-01 for 1 passenger(s).");
        assertThat(tools.searchFlights("DXB", "LHR", "2026-12-01", null).message()).contains("unavailable");
    }

    @Test
    void looksUpBookingsWithTheCallersTokenAndHidesPersonalData() {
        server.expect(requestTo("http://upstream/api/v1/bookings/K7QX2M"))
                .andExpect(header("Authorization", "Bearer caller-token"))
                .andRespond(withSuccess(BOOKING, MediaType.APPLICATION_JSON));

        BookingSummary summary = new BookingLookupTools(builder.build())
                .getMyBooking("k7qx2m",
                        new ToolContext(Map.of(BookingLookupTools.AUTHORIZATION, "Bearer caller-token")));

        server.verify();
        assertThat(summary.found()).isEqualTo("yes");
        assertThat(summary.status()).isEqualTo("CONFIRMED");
        assertThat(summary.flight()).isEqualTo("SB101 DXB-LHR");
        assertThat(summary.ticketed()).isTrue();
        assertThat(summary.paid()).isEqualTo("420.00 USD");
        assertThat(summary.toString()).doesNotContain("Ada", "Lovelace", "ada@example.test");
    }

    @Test
    void bookingLookupFailsSafely() {
        server.expect(requestTo("http://upstream/api/v1/bookings/ZZZZZZ")).andRespond(withResourceNotFound());
        server.expect(requestTo("http://upstream/api/v1/bookings/YYYYYY")).andRespond(withServerError());
        BookingLookupTools tools = new BookingLookupTools(builder.build());
        ToolContext signedIn = new ToolContext(Map.of(BookingLookupTools.AUTHORIZATION, "Bearer t"));

        assertThat(tools.getMyBooking("ZZZZZZ", signedIn).found()).contains("No booking ZZZZZZ");
        assertThat(tools.getMyBooking("YYYYYY", signedIn).found()).contains("cannot be looked up");
        assertThat(tools.getMyBooking("../admin", signedIn).found()).contains("6 letters and digits");
        assertThat(tools.getMyBooking("K7QX2M", new ToolContext(Map.of())).found()).contains("not signed in");
        server.verify();
    }
}
