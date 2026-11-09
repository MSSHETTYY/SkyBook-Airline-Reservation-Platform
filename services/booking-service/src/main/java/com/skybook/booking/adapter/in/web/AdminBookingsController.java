package com.skybook.booking.adapter.in.web;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.booking.adapter.in.web.api.AdminBookingsApi;
import com.skybook.booking.adapter.in.web.api.model.Booking;
import com.skybook.booking.adapter.in.web.api.model.BookingPage;
import com.skybook.booking.adapter.in.web.api.model.BookingStatus;
import com.skybook.booking.adapter.in.web.api.model.ManualRefundRequest;
import com.skybook.booking.application.port.in.BookingQueries;
import com.skybook.booking.application.port.in.ManualRefundUseCase;
import com.skybook.common.security.Roles;

/** Booking servicing for agents and admins. */
@RestController
class AdminBookingsController implements AdminBookingsApi {

    private final BookingQueries queries;
    private final ManualRefundUseCase refunds;

    AdminBookingsController(BookingQueries queries, ManualRefundUseCase refunds) {
        this.queries = queries;
        this.refunds = refunds;
    }

    @Override
    @PreAuthorize(Roles.IS_ADMIN)
    public ResponseEntity<Booking> issueManualRefund(String bookingReference, String idempotencyKey,
                                                     ManualRefundRequest request) {
        var amount = com.skybook.common.money.Money.of(request.getAmount().getAmount(),
                request.getAmount().getCurrency());
        return ResponseEntity.accepted().body(BookingApiMapper.toApi(
                refunds.refund(bookingReference, amount, request.getNote(), idempotencyKey)));
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
