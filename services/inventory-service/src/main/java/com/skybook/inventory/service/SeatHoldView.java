package com.skybook.inventory.service;

import java.time.Instant;
import java.util.List;

import com.skybook.common.money.Money;
import com.skybook.inventory.domain.CabinClass;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.HoldStatus;
import com.skybook.inventory.domain.SeatHold;

/** A hold plus the flight details booking-service needs to snapshot into the booking. */
public record SeatHoldView(
        String holdId,
        Long flightId,
        String flightNumber,
        String origin,
        String destination,
        Instant departureTime,
        HoldStatus status,
        List<Line> seats,
        Money totalPrice,
        Instant expiresAt,
        String bookingReference) {

    public record Line(String seatNumber, CabinClass cabinClass, Money price) {
    }

    static SeatHoldView of(SeatHold hold, Flight flight) {
        String currency = hold.total().currencyCode();
        List<Line> lines = hold.getLines().stream()
                .map(line -> new Line(line.getSeatNumber(), line.getCabinClass(),
                        Money.of(line.getPriceAmount(), currency)))
                .toList();
        return new SeatHoldView(hold.getId(), flight.getId(), flight.getFlightNumber(), flight.getOrigin(),
                flight.getDestination(), flight.getDepartureTime(), hold.getStatus(), lines, hold.total(),
                hold.getExpiresAt(), hold.getBookingReference());
    }
}
