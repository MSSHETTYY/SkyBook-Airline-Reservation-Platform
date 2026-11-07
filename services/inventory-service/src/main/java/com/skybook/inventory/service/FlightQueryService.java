package com.skybook.inventory.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.skybook.common.error.ResourceNotFoundException;
import com.skybook.inventory.domain.Flight;
import com.skybook.inventory.domain.FlightStatus;
import com.skybook.inventory.repository.FlightRepository;

/** Read-only flight search. Availability comes from the (cached) seat-map read model. */
@Service
public class FlightQueryService {

    private final FlightRepository flights;
    private final SeatMapService seatMaps;

    public FlightQueryService(FlightRepository flights, SeatMapService seatMaps) {
        this.flights = flights;
        this.seatMaps = seatMaps;
    }

    /** Scheduled flights departing on {@code date} (UTC) with at least {@code passengers} free seats. */
    @Transactional(readOnly = true)
    public List<FlightView> search(String origin, String destination, LocalDate date, int passengers) {
        List<Flight> matches = flights.searchRoute(origin, destination, FlightStatus.SCHEDULED,
                date.atStartOfDay().toInstant(ZoneOffset.UTC),
                date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC));
        return matches.stream()
                .map(flight -> FlightView.of(flight, seatMaps.seatMap(flight.getId())))
                .filter(view -> view.totalAvailable() >= passengers)
                .toList();
    }

    @Transactional(readOnly = true)
    public FlightView flight(Long flightId) {
        Flight flight = flights.findById(flightId).orElseThrow(() -> new ResourceNotFoundException("Flight", flightId));
        return FlightView.of(flight, seatMaps.seatMap(flightId));
    }
}
