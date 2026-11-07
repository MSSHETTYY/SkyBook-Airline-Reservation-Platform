package com.skybook.inventory.service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import com.skybook.common.money.Money;
import com.skybook.inventory.domain.CabinClass;

/**
 * Read model of a flight's seats: availability and current prices. This is what is cached in Redis,
 * so it contains only plain values (no entities) and is safe to serialise.
 */
public record SeatMapSnapshot(Long flightId, String currency, List<SeatView> seats) {

    public record SeatView(String seatNumber, CabinClass cabinClass, boolean available, BigDecimal price) {
    }

    public int available(CabinClass cabin) {
        return (int) seats.stream().filter(s -> s.available() && s.cabinClass() == cabin).count();
    }

    public int totalAvailable() {
        return (int) seats.stream().filter(SeatView::available).count();
    }

    /** Cheapest available seat; falls back to the cheapest seat overall when the flight is full. */
    public Optional<Money> fromPrice() {
        Comparator<SeatView> byPrice = Comparator.comparing(SeatView::price);
        return seats.stream().filter(SeatView::available).min(byPrice)
                .or(() -> seats.stream().min(byPrice))
                .map(seat -> Money.of(seat.price(), currency));
    }
}
