package com.skybook.inventory.service;

import java.util.Optional;

/**
 * Cache of seat-map read models. Implementations must never fail the caller: the database is the
 * source of truth, so a cache outage only costs latency.
 */
public interface SeatMapCache {

    Optional<SeatMapSnapshot> get(Long flightId);

    void put(SeatMapSnapshot snapshot);

    void evict(Long flightId);

    /** Used when caching is disabled (tests, or a deliberate switch-off in production). */
    SeatMapCache NONE = new SeatMapCache() {
        @Override
        public Optional<SeatMapSnapshot> get(Long flightId) {
            return Optional.empty();
        }

        @Override
        public void put(SeatMapSnapshot snapshot) {
            // caching disabled
        }

        @Override
        public void evict(Long flightId) {
            // caching disabled
        }
    };
}
