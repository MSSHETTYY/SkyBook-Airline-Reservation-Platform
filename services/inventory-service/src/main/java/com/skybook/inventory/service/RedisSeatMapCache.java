package com.skybook.inventory.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.ObjectMapper;

/**
 * Redis-backed seat-map cache ({@code inventory:seatmap:<flightId>} -> JSON).
 *
 * <p>Failure handling: any Redis error is logged and treated as a cache miss, and the cache then stays
 * bypassed for {@link #BACKOFF} so a Redis outage does not add a timeout to every request.
 */
public class RedisSeatMapCache implements SeatMapCache {

    static final String KEY_PREFIX = "inventory:seatmap:";
    static final Duration BACKOFF = Duration.ofSeconds(30);

    private static final Logger LOG = LoggerFactory.getLogger(RedisSeatMapCache.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final Duration ttl;
    private final Clock clock;
    private final AtomicReference<Instant> bypassUntil = new AtomicReference<>(Instant.MIN);

    public RedisSeatMapCache(StringRedisTemplate redis, ObjectMapper objectMapper, Duration ttl, Clock clock) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<SeatMapSnapshot> get(Long flightId) {
        if (bypassed()) {
            return Optional.empty();
        }
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + flightId);
            return json == null ? Optional.empty() : Optional.of(objectMapper.readValue(json, SeatMapSnapshot.class));
        } catch (RuntimeException ex) {
            onFailure("read", flightId, ex);
            return Optional.empty();
        }
    }

    @Override
    public void put(SeatMapSnapshot snapshot) {
        if (bypassed()) {
            return;
        }
        try {
            redis.opsForValue().set(KEY_PREFIX + snapshot.flightId(), objectMapper.writeValueAsString(snapshot), ttl);
        } catch (RuntimeException ex) {
            onFailure("write", snapshot.flightId(), ex);
        }
    }

    @Override
    public void evict(Long flightId) {
        // Evictions are attempted even while bypassed: a stale entry is worse than a slow call.
        try {
            redis.delete(KEY_PREFIX + flightId);
        } catch (RuntimeException ex) {
            onFailure("evict", flightId, ex);
        }
    }

    private boolean bypassed() {
        return clock.instant().isBefore(bypassUntil.get());
    }

    private void onFailure(String operation, Long flightId, RuntimeException ex) {
        bypassUntil.set(clock.instant().plus(BACKOFF));
        LOG.warn("Seat-map cache {} failed for flight {}; bypassing cache for {}s: {}",
                operation, flightId, BACKOFF.toSeconds(), ex.toString());
    }
}
