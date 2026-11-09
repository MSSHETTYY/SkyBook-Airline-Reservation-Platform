package com.skybook.booking.adapter.in.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import com.skybook.booking.adapter.in.web.api.model.Booking;
import com.skybook.booking.adapter.in.web.api.model.BookingPage;
import com.skybook.booking.adapter.in.web.api.model.BookingStatus;
import com.skybook.booking.adapter.in.web.api.model.FlightSnapshot;
import com.skybook.booking.adapter.in.web.api.model.Money;
import com.skybook.booking.adapter.in.web.api.model.Passenger;
import com.skybook.booking.adapter.in.web.api.model.PaymentInfo;
import com.skybook.booking.application.port.in.PageResult;

/** Maps the domain aggregate to the generated API models (and nothing else crosses this boundary). */
final class BookingApiMapper {

    private BookingApiMapper() {
    }

    static Booking toApi(com.skybook.booking.domain.model.Booking booking) {
        var flight = booking.getFlight();
        Booking api = new Booking()
                .bookingReference(booking.getReference().value())
                .status(BookingStatus.fromValue(booking.getStatus().name()))
                .flight(new FlightSnapshot()
                        .flightId(flight.flightId())
                        .flightNumber(flight.flightNumber())
                        .origin(flight.origin())
                        .destination(flight.destination())
                        .departureTime(toOffset(flight.departureTime())))
                .passengers(booking.getPassengers().stream()
                        .map(p -> new Passenger()
                                .firstName(p.firstName())
                                .lastName(p.lastName())
                                .seatNumber(p.seatNumber())
                                .ticketNumber(p.ticketNumber()))
                        .toList())
                .totalPrice(toApi(booking.getTotalPrice()))
                .contactEmail(booking.getContactEmail())
                .amountPaid(toApi(booking.getAmountPaid()))
                .amountRefunded(toApi(booking.getAmountRefunded()))
                .createdAt(toOffset(booking.getCreatedAt()));
        var pending = booking.getPendingChange();
        if (booking.getStatus() == com.skybook.booking.domain.model.BookingStatus.CHANGE_PENDING_PAYMENT) {
            api.changeAmountDue(toApi(pending.amountDue()));
            api.payment(new PaymentInfo()
                    .paymentId(pending.paymentId())
                    .status(pending.paid() ? "SUCCEEDED" : "REQUIRES_PAYMENT")
                    .clientSecret(booking.getPaymentClientSecret()));
            return api;
        }
        if (booking.getStatus() == com.skybook.booking.domain.model.BookingStatus.PENDING_PAYMENT) {
            api.holdExpiresAt(toOffset(booking.getHoldExpiresAt()));
        }
        if (booking.getPaymentId() != null) {
            api.payment(new PaymentInfo()
                    .paymentId(booking.getPaymentId())
                    .status(booking.getStatus() == com.skybook.booking.domain.model.BookingStatus.PENDING_PAYMENT
                            ? "REQUIRES_PAYMENT" : "SUCCEEDED")
                    .clientSecret(booking.getPaymentClientSecret()));
        }
        return api;
    }

    static BookingPage toApi(PageResult<com.skybook.booking.domain.model.Booking> page) {
        return new BookingPage()
                .items(page.items().stream().map(BookingApiMapper::toApi).toList())
                .page(page.page())
                .size(page.size())
                .totalElements(page.totalElements());
    }

    static Money toApi(com.skybook.common.money.Money money) {
        return new Money().amount(money.amount()).currency(money.currencyCode());
    }

    static OffsetDateTime toOffset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
