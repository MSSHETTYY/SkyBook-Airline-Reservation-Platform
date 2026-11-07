package com.skybook.booking.domain.model;

import java.time.Instant;
import java.util.Objects;

/**
 * The flight as it was when the booking was made. Copied into the booking so the booking service can
 * answer "what did I book?" without calling inventory, and so later schedule edits don't rewrite history.
 */
public record FlightSnapshot(Long flightId, String flightNumber, String origin, String destination,
                             Instant departureTime) {

    public FlightSnapshot {
        Objects.requireNonNull(flightId, "flightId");
        Objects.requireNonNull(flightNumber, "flightNumber");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departureTime, "departureTime");
    }
}
