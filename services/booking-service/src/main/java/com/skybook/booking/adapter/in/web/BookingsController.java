package com.skybook.booking.adapter.in.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.booking.adapter.in.web.api.BookingsApi;
import com.skybook.booking.adapter.in.web.api.model.Booking;
import com.skybook.booking.adapter.in.web.api.model.BookingPage;
import com.skybook.booking.adapter.in.web.api.model.CancellationQuote;
import com.skybook.booking.adapter.in.web.api.model.DateChangeRequest;
import com.skybook.booking.adapter.in.web.api.model.CreateBookingRequest;
import com.skybook.booking.application.port.in.BookingQueries;
import com.skybook.booking.application.port.in.CancelBookingUseCase;
import com.skybook.booking.application.port.in.ChangeBookingUseCase;
import com.skybook.booking.application.port.in.CreateBookingUseCase;
import com.skybook.booking.application.port.in.CreateBookingUseCase.CreateBookingCommand;
import com.skybook.booking.application.port.in.CreateBookingUseCase.PassengerDetails;
import com.skybook.booking.domain.policy.RefundPolicy;
import com.skybook.common.security.Caller;
import com.skybook.common.security.Roles;

/** Customer-facing booking endpoints. The customer id always comes from the token, never from the request. */
@RestController
class BookingsController implements BookingsApi {

    private final CreateBookingUseCase createBooking;
    private final BookingQueries queries;
    private final CancelBookingUseCase cancellation;
    private final ChangeBookingUseCase change;

    BookingsController(CreateBookingUseCase createBooking, BookingQueries queries, CancelBookingUseCase cancellation,
                       ChangeBookingUseCase change) {
        this.createBooking = createBooking;
        this.queries = queries;
        this.cancellation = cancellation;
        this.change = change;
    }

    @Override
    @PreAuthorize("hasAnyRole('CUSTOMER', 'AGENT', 'ADMIN')")
    public ResponseEntity<CancellationQuote> getCancellationQuote(String bookingReference) {
        Caller caller = Caller.current();
        RefundPolicy.Quote quote = cancellation.quoteCancellation(bookingReference, caller.subject(),
                caller.isStaff());
        return ResponseEntity.ok(new CancellationQuote()
                .bookingReference(bookingReference)
                .refundAmount(BookingApiMapper.toApi(quote.amount()))
                .refundPercentage(quote.percentage())
                .rule(quote.rule()));
    }

    @Override
    @PreAuthorize("hasAnyRole('CUSTOMER', 'AGENT', 'ADMIN')")
    public ResponseEntity<Booking> cancelBooking(String bookingReference) {
        Caller caller = Caller.current();
        return ResponseEntity.accepted().body(BookingApiMapper.toApi(
                cancellation.cancel(bookingReference, caller.subject(), caller.isStaff())));
    }

    @Override
    @PreAuthorize("hasAnyRole('CUSTOMER', 'AGENT', 'ADMIN')")
    public ResponseEntity<Booking> changeBookingDate(String bookingReference, String idempotencyKey,
                                                     DateChangeRequest request) {
        Caller caller = Caller.current();
        return ResponseEntity.ok(BookingApiMapper.toApi(change.changeDate(new ChangeBookingUseCase.ChangeDateCommand(
                bookingReference, caller.subject(), caller.isStaff(), request.getNewFlightId(),
                request.getSeatNumbers(), idempotencyKey))));
    }

    @Override
    @PreAuthorize(Roles.IS_CUSTOMER)
    public ResponseEntity<Booking> createBooking(String idempotencyKey, CreateBookingRequest request) {
        Caller caller = Caller.current();
        var booking = createBooking.create(new CreateBookingCommand(
                caller.subject(),
                idempotencyKey,
                request.getFlightId(),
                request.getContactEmail(),
                request.getPassengers().stream()
                        .map(p -> new PassengerDetails(p.getFirstName(), p.getLastName(), p.getDateOfBirth(),
                                p.getSeatNumber()))
                        .toList()));
        return ResponseEntity.created(URI.create("/api/v1/bookings/" + booking.getReference().value()))
                .body(BookingApiMapper.toApi(booking));
    }

    @Override
    @PreAuthorize(Roles.IS_CUSTOMER)
    public ResponseEntity<BookingPage> listMyBookings(Integer page, Integer size) {
        return ResponseEntity.ok(BookingApiMapper.toApi(queries.listForCustomer(Caller.current().subject(),
                page == null ? 0 : page, size == null ? 20 : size)));
    }

    @Override
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Booking> getBooking(String bookingReference) {
        Caller caller = Caller.current();
        return ResponseEntity.ok(BookingApiMapper.toApi(
                queries.get(bookingReference, caller.subject(), caller.isStaff())));
    }
}
