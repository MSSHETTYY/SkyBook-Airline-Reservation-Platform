package com.skybook.inventory.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * In-process notification that a flight's seats changed. The cache is evicted only after the
 * transaction commits; evicting earlier would let a reader re-cache the old state while the change is
 * still in flight. A reader that loaded just before the commit can still re-cache stale data (the
 * classic cache-aside race); that window is bounded by the cache TTL, and seat allocation never reads
 * the cache, so the worst case is a seat shown as available for a few seconds, never an overbooking.
 */
public record SeatInventoryChanged(Long flightId) {

    /** Evicts the seat-map read model once the change is durable. */
    @Component
    static class CacheEvictor {

        private final SeatMapCache cache;

        CacheEvictor(SeatMapCache cache) {
            this.cache = cache;
        }

        @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
        void on(SeatInventoryChanged event) {
            cache.evict(event.flightId());
        }
    }
}
