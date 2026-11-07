package com.skybook.booking.adapter.in.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.booking.adapter.in.web.api.BookingsApi;
import com.skybook.booking.adapter.in.web.api.model.Booking;
import com.skybook.booking.adapter.in.web.api.model.BookingPage;
import com.skybook.booking.adapter.in.web.api.model.CreateBookingRequest;
import com.skybook.booking.application.port.in.BookingQueries;
import com.skybook.booking.application.port.in.CreateBookingUseCase;
import com.skybook.booking.application.port.in.CreateBookingUseCase.CreateBookingCommand;
import com.skybook.booking.application.port.in.CreateBookingUseCase.PassengerDetails;
import com.skybook.common.security.Caller;
import com.skybook.common.security.Roles;

/** Customer-facing booking endpoints. The customer id always comes from the token, never from the request. */
@RestController
class BookingsController implements BookingsApi {

    private final CreateBookingUseCase createBooking;
    private final BookingQueries queries;

    BookingsController(CreateBookingUseCase createBooking, BookingQueries queries) {
        this.createBooking = createBooking;
        this.queries = queries;
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
