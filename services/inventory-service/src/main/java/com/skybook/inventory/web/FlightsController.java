package com.skybook.inventory.web;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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

    static final Instant SEARCH_V1_DEPRECATED = Instant.parse("2026-11-09T00:00:00Z");
    static final Instant SEARCH_V1_SUNSET = Instant.parse("2027-06-30T23:59:59Z");

    private final FlightQueryService queries;
    private final SeatMapService seatMaps;

    FlightsController(FlightQueryService queries, SeatMapService seatMaps) {
        this.queries = queries;
        this.seatMaps = seatMaps;
    }

    /**
     * Superseded by {@code GET /api/v2/flights} (ADR-0020). Clients are told in-band: RFC 9745
     * {@code Deprecation}, RFC 8594 {@code Sunset} and a {@code successor-version} link.
     */
    @Override
    public ResponseEntity<List<FlightSummary>> searchFlights(String origin, String destination, LocalDate date,
                                                             Integer passengers) {
        int seatsNeeded = passengers == null ? 1 : passengers;
        return ResponseEntity.ok()
                .header("Deprecation", "@" + SEARCH_V1_DEPRECATED.getEpochSecond())
                .header("Sunset", DateTimeFormatter.RFC_1123_DATE_TIME.format(SEARCH_V1_SUNSET.atZone(ZoneOffset.UTC)))
                .header("Link", "</api/v2/flights>; rel=\"successor-version\"")
                .body(queries.search(origin, destination, date, seatsNeeded).stream()
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
