package com.skybook.assistant.tools;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Read-only flight search for the model, over inventory-service's public API v2 (ADR-0020).
 *
 * <p>Arguments come from the language model, so they are validated like untrusted input, and failures are
 * returned as text the model can relay ("no flights", "invalid airport") rather than thrown.
 */
public class FlightSearchTools {

    private static final Pattern AIRPORT = Pattern.compile("^[A-Z]{3}$");
    private static final int MAX_RESULTS = 5;

    private final RestClient inventory;

    public FlightSearchTools(RestClient inventory) {
        this.inventory = inventory;
    }

    record Money(BigDecimal amount, String currency) {
    }

    record Cabin(String cabinClass, int seatsAvailable, Money fromPrice) {
    }

    record Offer(String flightNumber, String origin, String destination, OffsetDateTime departureTime,
                 OffsetDateTime arrivalTime, int durationMinutes, List<Cabin> cabins) {
    }

    record OfferPage(List<Offer> items, long totalElements) {
    }

    /** What the model sees: compact, one line per flight. */
    public record FlightOption(String flightNumber, String route, String departure, String arrival,
                               String duration, String economy, String business) {
    }

    public record SearchResult(String message, List<FlightOption> flights) {
    }

    @Tool(description = """
            Search scheduled SkyBook flights between two airports for a departure date. Returns at most five
            flights with departure and arrival times (UTC), seats left and the cheapest price per cabin.""")
    public SearchResult searchFlights(
            @ToolParam(description = "Origin airport, 3-letter IATA code, e.g. DXB") String origin,
            @ToolParam(description = "Destination airport, 3-letter IATA code, e.g. LHR") String destination,
            @ToolParam(description = "Departure date as YYYY-MM-DD") String departureDate,
            @ToolParam(description = "Number of passengers, 1 to 9", required = false) Integer passengers) {
        String from = normalise(origin);
        String to = normalise(destination);
        if (!AIRPORT.matcher(from).matches() || !AIRPORT.matcher(to).matches()) {
            return new SearchResult("Airports must be 3-letter IATA codes such as DXB or LHR.", List.of());
        }
        LocalDate date;
        try {
            date = LocalDate.parse(departureDate.trim());
        } catch (DateTimeParseException | NullPointerException e) {
            return new SearchResult("The departure date must be in the format YYYY-MM-DD.", List.of());
        }
        int travellers = passengers == null ? 1 : Math.clamp(passengers, 1, 9);
        try {
            OfferPage page = inventory.get()
                    .uri(uri -> uri.path("/api/v2/flights")
                            .queryParam("origin", from)
                            .queryParam("destination", to)
                            .queryParam("departureFrom", date)
                            .queryParam("passengers", travellers)
                            .queryParam("size", MAX_RESULTS)
                            .build())
                    .retrieve()
                    .body(OfferPage.class);
            List<FlightOption> options = page == null ? List.of() : page.items().stream()
                    .map(FlightSearchTools::option).toList();
            String message = options.isEmpty()
                    ? "No flights from %s to %s on %s for %d passenger(s).".formatted(from, to, date, travellers)
                    : "%d flight(s) found.".formatted(page.totalElements());
            return new SearchResult(message, options);
        } catch (RestClientException e) {
            return new SearchResult("Flight search is unavailable right now; please try again shortly.", List.of());
        }
    }

    private static String normalise(String airport) {
        return airport == null ? "" : airport.trim().toUpperCase(Locale.ROOT);
    }

    private static FlightOption option(Offer offer) {
        return new FlightOption(offer.flightNumber(), offer.origin() + "-" + offer.destination(),
                offer.departureTime().toString(), offer.arrivalTime().toString(),
                "%dh %02dm".formatted(offer.durationMinutes() / 60, offer.durationMinutes() % 60),
                cabin(offer, "ECONOMY"), cabin(offer, "BUSINESS"));
    }

    private static String cabin(Offer offer, String cabinClass) {
        return offer.cabins().stream()
                .filter(c -> c.cabinClass().equals(cabinClass))
                .findFirst()
                .map(c -> c.fromPrice() == null
                        ? "sold out"
                        : "%d seats from %s %s".formatted(c.seatsAvailable(), c.fromPrice().amount().toPlainString(),
                                c.fromPrice().currency()))
                .orElse("not offered");
    }
}
