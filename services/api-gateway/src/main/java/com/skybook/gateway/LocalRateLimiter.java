package com.skybook.gateway;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;

import reactor.core.publisher.Mono;

/**
 * In-memory token-bucket rate limiter for a <b>single gateway instance</b> (local development without
 * Redis, and tests). Production uses Spring Cloud Gateway's {@code RedisRateLimiter}, which shares the
 * buckets across replicas; with N replicas this limiter would allow N times the configured rate.
 */
public class LocalRateLimiter implements RateLimiter<LocalRateLimiter.Config> {

    static final Duration IDLE_EVICTION = Duration.ofMinutes(10);
    private static final int MAX_TRACKED_KEYS = 10_000;

    private final int replenishRate;
    private final int burstCapacity;
    private final LongSupplier nanoClock;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public LocalRateLimiter(int replenishRate, int burstCapacity) {
        this(replenishRate, burstCapacity, System::nanoTime);
    }

    LocalRateLimiter(int replenishRate, int burstCapacity, LongSupplier nanoClock) {
        if (replenishRate <= 0 || burstCapacity < replenishRate) {
            throw new IllegalArgumentException("Need 0 < replenishRate <= burstCapacity");
        }
        this.replenishRate = replenishRate;
        this.burstCapacity = burstCapacity;
        this.nanoClock = nanoClock;
    }

    private record Bucket(double tokens, long refilledAtNanos) {
    }

    @Override
    public Mono<Response> isAllowed(String routeId, String id) {
        long now = nanoClock.getAsLong();
        evictIdleBucketsIfCrowded(now);
        boolean[] allowed = new boolean[1];
        Bucket bucket = buckets.compute(id, (key, current) -> {
            double refill = current == null ? 0 : (now - current.refilledAtNanos()) / 1e9 * replenishRate;
            double tokens = current == null ? burstCapacity : Math.min(burstCapacity, current.tokens() + refill);
            allowed[0] = tokens >= 1;
            return new Bucket(allowed[0] ? tokens - 1 : tokens, now);
        });
        return Mono.just(new Response(allowed[0], Map.of(
                "X-RateLimit-Remaining", String.valueOf((long) bucket.tokens()),
                "X-RateLimit-Burst-Capacity", String.valueOf(burstCapacity),
                "X-RateLimit-Replenish-Rate", String.valueOf(replenishRate))));
    }

    private void evictIdleBucketsIfCrowded(long now) {
        if (buckets.size() > MAX_TRACKED_KEYS) {
            buckets.entrySet().removeIf(entry -> now - entry.getValue().refilledAtNanos() > IDLE_EVICTION.toNanos());
        }
    }

    int trackedKeys() {
        return buckets.size();
    }

    @Override
    public Map<String, Config> getConfig() {
        return Map.of();
    }

    @Override
    public Class<Config> getConfigClass() {
        return Config.class;
    }

    @Override
    public Config newConfig() {
        return new Config();
    }

    /** Per-route configuration is not supported; the limits are global for this limiter. */
    public static class Config {
    }
}
