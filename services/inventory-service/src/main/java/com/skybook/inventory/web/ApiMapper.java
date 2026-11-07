package com.skybook.inventory.web;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.skybook.inventory.api.v1.model.Availability;
import com.skybook.inventory.api.v1.model.CabinClass;
import com.skybook.inventory.api.v1.model.FlightStatus;
import com.skybook.inventory.api.v1.model.FlightSummary;
import com.skybook.inventory.api.v1.model.HeldSeat;
import com.skybook.inventory.api.v1.model.HoldStatus;
import com.skybook.inventory.api.v1.model.Money;
import com.skybook.inventory.api.v1.model.Seat;
import com.skybook.inventory.api.v1.model.SeatHold;
import com.skybook.inventory.api.v1.model.SeatMap;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.service.FlightView;
import com.skybook.inventory.service.SeatHoldView;
import com.skybook.inventory.service.SeatMapSnapshot;

/** Maps service-layer views to the generated API models, keeping generated types out of the service layer. */
final class ApiMapper {

    private ApiMapper() {
    }

    static FlightSummary toApi(FlightView view) {
        Flight flight = view.flight();
        return new FlightSummary()
                .flightId(flight.getId())
                .flightNumber(flight.getFlightNumber())
                .origin(flight.getOrigin())
                .destination(flight.getDestination())
                .departureTime(toOffset(flight.getDepartureTime()))
                .arrivalTime(toOffset(flight.getArrivalTime()))
                .aircraftType(flight.getAircraftType())
                .status(FlightStatus.fromValue(flight.getStatus().name()))
                .availableSeats(new Availability().economy(view.availableEconomy()).business(view.availableBusiness()))
                .fromPrice(toApi(view.fromPrice()));
    }

    static SeatMap toApi(SeatMapSnapshot snapshot) {
        return new SeatMap()
                .flightId(snapshot.flightId())
                .seats(snapshot.seats().stream()
                        .map(seat -> new Seat()
                                .seatNumber(seat.seatNumber())
                                .cabinClass(CabinClass.fromValue(seat.cabinClass().name()))
                                .available(seat.available())
                                .price(new Money().amount(seat.price()).currency(snapshot.currency())))
                        .toList());
    }

    static SeatHold toApi(SeatHoldView hold) {
        return new SeatHold()
                .holdId(UUID.fromString(hold.holdId()))
                .flightId(hold.flightId())
                .flightNumber(hold.flightNumber())
                .origin(hold.origin())
                .destination(hold.destination())
                .departureTime(toOffset(hold.departureTime()))
                .status(HoldStatus.fromValue(hold.status().name()))
                .seats(hold.seats().stream()
                        .map(line -> new HeldSeat()
                                .seatNumber(line.seatNumber())
                                .cabinClass(CabinClass.fromValue(line.cabinClass().name()))
                                .price(toApi(line.price())))
                        .toList())
                .totalPrice(toApi(hold.totalPrice()))
                .expiresAt(toOffset(hold.expiresAt()))
                .bookingReference(hold.bookingReference());
    }

    static Money toApi(com.skybook.common.money.Money money) {
        return new Money().amount(money.amount()).currency(money.currencyCode());
    }

    static com.skybook.common.money.Money toDomain(Money money) {
        return com.skybook.common.money.Money.of(money.getAmount(), money.getCurrency());
    }

    static OffsetDateTime toOffset(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
