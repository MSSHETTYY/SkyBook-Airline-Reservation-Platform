package com.skybook.booking.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.booking.adapter.in.web.api.AdminBookingsApi;
import com.skybook.booking.adapter.in.web.api.model.BookingPage;
import com.skybook.booking.adapter.in.web.api.model.BookingStatus;
import com.skybook.booking.application.port.in.BookingQueries;
import com.skybook.common.security.Roles;

/** Booking servicing for agents and admins. */
@RestController
class AdminBookingsController implements AdminBookingsApi {

    private final BookingQueries queries;

    AdminBookingsController(BookingQueries queries) {
        this.queries = queries;
    }

    @Override
    @PreAuthorize(Roles.IS_STAFF)
    public ResponseEntity<BookingPage> searchBookings(BookingStatus status, Long flightId, Integer page, Integer size) {
        var domainStatus = status == null ? null
                : com.skybook.booking.domain.model.BookingStatus.valueOf(status.getValue());
        return ResponseEntity.ok(BookingApiMapper.toApi(queries.search(domainStatus, flightId,
                page == null ? 0 : page, size == null ? 20 : size)));
    }
}
