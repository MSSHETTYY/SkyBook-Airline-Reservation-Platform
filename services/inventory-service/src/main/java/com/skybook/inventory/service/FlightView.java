package com.skybook.inventory.service;

import com.skybook.common.money.Money;
import com.skybook.inventory.domain.CabinClass;
import com.skybook.inventory.domain.Flight;

/** A flight together with its current availability, as returned by search and flight look-ups. */
public record FlightView(Flight flight, int availableEconomy, int availableBusiness, Money fromPrice) {

    static FlightView of(Flight flight, SeatMapSnapshot seatMap) {
        return new FlightView(flight,
                seatMap.available(CabinClass.ECONOMY),
                seatMap.available(CabinClass.BUSINESS),
                seatMap.fromPrice().orElse(flight.baseFare()));
    }

    public int totalAvailable() {
        return availableEconomy + availableBusiness;
    }
}
