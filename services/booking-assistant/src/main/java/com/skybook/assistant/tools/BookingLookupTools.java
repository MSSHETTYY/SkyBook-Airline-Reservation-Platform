package com.skybook.assistant.tools;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Looks up one of the caller's bookings in booking-service, <strong>with the caller's own access token</strong>
 * (passed in the tool context, never visible to the model). booking-service therefore enforces ownership
 * exactly as for the web UI: a prompt that names someone else's reference gets "not found".
 */
public class BookingLookupTools {

    /** Tool-context key holding the caller's {@code Authorization} header value. */
    public static final String AUTHORIZATION = "authorization";

    private static final Pattern REFERENCE = Pattern.compile("^[A-Z2-9]{6}$");

    private final RestClient bookings;

    public BookingLookupTools(RestClient bookings) {
        this.bookings = bookings;
    }

    record Money(BigDecimal amount, String currency) {
    }

    record Flight(String flightNumber, String origin, String destination, OffsetDateTime departureTime) {
    }

    record Passenger(String seatNumber, String ticketNumber) {
    }

    record Booking(String bookingReference, String status, Flight flight, List<Passenger> passengers,
                   Money totalPrice, Money amountPaid, Money amountRefunded) {
    }

    /** What the model sees: no names, dates of birth or e-mail addresses. */
    public record BookingSummary(String found, String reference, String status, String flight, String departure,
                                 int passengers, boolean ticketed, String total, String paid, String refunded) {

        static BookingSummary notFound(String message) {
            return new BookingSummary(message, null, null, null, null, 0, false, null, null, null);
        }
    }

    @Tool(description = """
            Look up one of the signed-in customer's own bookings by its 6-character booking reference.
            Returns status, flight, departure time, passenger count, ticketing state and amounts.""")
    public BookingSummary getMyBooking(
            @ToolParam(description = "Booking reference, 6 characters, e.g. K7QX2M") String bookingReference,
            ToolContext context) {
        String reference = bookingReference == null ? "" : bookingReference.trim().toUpperCase(Locale.ROOT);
        if (!REFERENCE.matcher(reference).matches()) {
            return BookingSummary.notFound("A booking reference has 6 letters and digits, e.g. K7QX2M.");
        }
        Object authorization = context == null ? null : context.getContext().get(AUTHORIZATION);
        if (!(authorization instanceof String header) || header.isBlank()) {
            return BookingSummary.notFound("The customer is not signed in.");
        }
        try {
            Booking booking = bookings.get()
                    .uri("/api/v1/bookings/{reference}", reference)
                    .header(HttpHeaders.AUTHORIZATION, header)
                    .retrieve()
                    .body(Booking.class);
            if (booking == null) {
                return BookingSummary.notFound("No booking " + reference + " was found for this customer.");
            }
            boolean ticketed = !booking.passengers().isEmpty()
                    && booking.passengers().stream().allMatch(p -> p.ticketNumber() != null);
            return new BookingSummary("yes", booking.bookingReference(), booking.status(),
                    "%s %s-%s".formatted(booking.flight().flightNumber(), booking.flight().origin(),
                            booking.flight().destination()),
                    booking.flight().departureTime().toString(), booking.passengers().size(), ticketed,
                    format(booking.totalPrice()), format(booking.amountPaid()), format(booking.amountRefunded()));
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.Forbidden e) {
            return BookingSummary.notFound("No booking " + reference + " was found for this customer.");
        } catch (RestClientException e) {
            return BookingSummary.notFound("Bookings cannot be looked up right now; please try again shortly.");
        }
    }

    private static String format(Money money) {
        return money == null ? null : money.amount().toPlainString() + " " + money.currency();
    }
}
