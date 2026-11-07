package com.skybook.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class LocalRateLimiterTest {

    private final AtomicLong nanos = new AtomicLong();
    private final LocalRateLimiter limiter = new LocalRateLimiter(2, 4, nanos::get);

    private boolean allowed(String key) {
        return limiter.isAllowed("route", key).block().isAllowed();
    }

    @Test
    void allowsABurstThenThrottles() {
        for (int i = 0; i < 4; i++) {
            assertThat(allowed("alice")).isTrue();
        }
        assertThat(allowed("alice")).isFalse();
        assertThat(allowed("bob")).as("buckets are per key").isTrue();
    }

    @Test
    void refillsAtTheReplenishRate() {
        for (int i = 0; i < 4; i++) {
            allowed("alice");
        }
        nanos.addAndGet(500_000_000L);
        assertThat(allowed("alice")).as("2 tokens/s -> 1 token after 0.5 s").isTrue();
        assertThat(allowed("alice")).isFalse();

        nanos.addAndGet(60_000_000_000L);
        for (int i = 0; i < 4; i++) {
            assertThat(allowed("alice")).as("refill is capped at the burst capacity").isTrue();
        }
        assertThat(allowed("alice")).isFalse();
    }

    @Test
    void exposesRateLimitHeaders() {
        var response = limiter.isAllowed("route", "carol").block();

        assertThat(response.getHeaders())
                .containsEntry("X-RateLimit-Remaining", "3")
                .containsEntry("X-RateLimit-Burst-Capacity", "4")
                .containsEntry("X-RateLimit-Replenish-Rate", "2");
    }

    @Test
    void validatesConfiguration() {
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalRateLimiter(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new LocalRateLimiter(5, 1));
        assertThat(limiter.getConfig()).isEmpty();
        assertThat(limiter.newConfig()).isNotNull();
        assertThat(limiter.getConfigClass()).isEqualTo(LocalRateLimiter.Config.class);
    }

    @Test
    void evictsIdleKeysWhenCrowded() {
        for (int i = 0; i <= 10_000; i++) {
            allowed("user-" + i);
        }
        nanos.addAndGet(LocalRateLimiter.IDLE_EVICTION.toNanos() + 1);
        allowed("fresh");

        assertThat(limiter.trackedKeys()).isEqualTo(1);
    }
}
