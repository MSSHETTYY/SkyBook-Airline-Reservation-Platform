package com.skybook.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import com.skybook.common.test.MutableClock;
import com.skybook.inventory.domain.CabinClass;

import tools.jackson.databind.json.JsonMapper;

class RedisSeatMapCacheTest {

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final MutableClock clock = new MutableClock(Instant.parse("2026-12-01T08:00:00Z"));
    private final RedisSeatMapCache cache =
            new RedisSeatMapCache(redis, JsonMapper.builder().build(), Duration.ofSeconds(30), clock);

    private final SeatMapSnapshot snapshot = new SeatMapSnapshot(7L, "USD",
            List.of(new SeatMapSnapshot.SeatView("1A", CabinClass.BUSINESS, true, new BigDecimal("250.00"))));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(values);
    }

    @Test
    void roundTripsSnapshotsAsJson() {
        cache.put(snapshot);
        org.mockito.ArgumentCaptor<String> json = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(values).set(org.mockito.ArgumentMatchers.eq("inventory:seatmap:7"), json.capture(),
                org.mockito.ArgumentMatchers.eq(Duration.ofSeconds(30)));

        when(values.get("inventory:seatmap:7")).thenReturn(json.getValue());
        assertThat(cache.get(7L)).contains(snapshot);
    }

    @Test
    void missReturnsEmpty() {
        assertThat(cache.get(8L)).isEmpty();
    }

    @Test
    void redisOutageIsAMissAndTheCacheIsBypassedForABackoffPeriod() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get(7L)).isEmpty();
        assertThat(cache.get(7L)).isEmpty();
        cache.put(snapshot);
        verify(values, times(1)).get(anyString());
        verify(values, never()).set(anyString(), anyString(), any(Duration.class));

        clock.advance(RedisSeatMapCache.BACKOFF);
        assertThat(cache.get(7L)).isEmpty();
        verify(values, times(2)).get(anyString());
    }

    @Test
    void evictionIsAttemptedEvenWhileBypassedAndNeverThrows() {
        when(values.get(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        cache.get(7L);

        cache.evict(7L);

        verify(redis).delete("inventory:seatmap:7");
    }

    @Test
    void noneCacheDoesNothing() {
        SeatMapCache.NONE.put(snapshot);
        SeatMapCache.NONE.evict(7L);
        assertThat(SeatMapCache.NONE.get(7L)).isEmpty();
    }
}
