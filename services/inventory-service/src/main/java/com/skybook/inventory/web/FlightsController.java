package com.skybook.inventory.web;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import com.skybook.inventory.api.v1.FlightsApi;
import com.skybook.inventory.api.v1.model.FlightSummary;
import com.skybook.inventory.api.v1.model.SeatMap;
import com.skybook.inventory.service.FlightQueryService;
import com.skybook.inventory.service.SeatMapService;

@RestController
class FlightsController implements FlightsApi {

    private final FlightQueryService queries;
    private final SeatMapService seatMaps;

    FlightsController(FlightQueryService queries, SeatMapService seatMaps) {
        this.queries = queries;
        this.seatMaps = seatMaps;
    }

    @Override
    public ResponseEntity<List<FlightSummary>> searchFlights(String origin, String destination, LocalDate date,
                                                             Integer passengers) {
        int seatsNeeded = passengers == null ? 1 : passengers;
        return ResponseEntity.ok(queries.search(origin, destination, date, seatsNeeded).stream()
                .map(ApiMapper::toApi)
                .toList());
    }

    @Override
    public ResponseEntity<FlightSummary> getFlight(Long flightId) {
        return ResponseEntity.ok(ApiMapper.toApi(queries.flight(flightId)));
    }

    @Override
    public ResponseEntity<SeatMap> getSeatMap(Long flightId) {
        return ResponseEntity.ok(ApiMapper.toApi(seatMaps.seatMap(flightId)));
    }
}
