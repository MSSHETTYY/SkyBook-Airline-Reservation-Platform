package com.skybook.inventory.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.common.money.Money;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.PricingPolicy;
import com.skybook.inventory.domain.Seat;
import com.skybook.inventory.repository.FlightRepository;
import com.skybook.inventory.repository.SeatRepository;

/** Query side for seat maps: cache-aside over the Oracle source of truth. */
@Service
public class SeatMapService {

    private final FlightRepository flights;
    private final SeatRepository seats;
    private final SeatMapCache cache;
    private final PricingPolicy pricing;
    private final Clock clock;

    public SeatMapService(FlightRepository flights, SeatRepository seats, SeatMapCache cache, PricingPolicy pricing,
                          Clock clock) {
        this.flights = flights;
        this.seats = seats;
        this.cache = cache;
        this.pricing = pricing;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public SeatMapSnapshot seatMap(Long flightId) {
        return cache.get(flightId).orElseGet(() -> {
            Flight flight = flights.findById(flightId)
                    .orElseThrow(() -> new ResourceNotFoundException("Flight", flightId));
            SeatMapSnapshot snapshot = snapshot(flight, seats.findByFlightIdOrderById(flightId), clock.instant());
            cache.put(snapshot);
            return snapshot;
        });
    }

    /** Builds the read model; prices reflect the flight's current load factor. */
    SeatMapSnapshot snapshot(Flight flight, List<Seat> flightSeats, Instant now) {
        int capacity = flightSeats.size();
        int occupied = (int) flightSeats.stream().filter(seat -> seat.isOccupiedAt(now)).count();
        Money baseFare = flight.baseFare();
        List<SeatMapSnapshot.SeatView> views = flightSeats.stream()
                .map(seat -> new SeatMapSnapshot.SeatView(
                        seat.getSeatNumber(),
                        seat.getCabinClass(),
                        seat.isAvailableAt(now),
                        pricing.priceFor(seat.getCabinClass(), baseFare, occupied, capacity).amount()))
                .toList();
        return new SeatMapSnapshot(flight.getId(), baseFare.currencyCode(), views);
    }
}
