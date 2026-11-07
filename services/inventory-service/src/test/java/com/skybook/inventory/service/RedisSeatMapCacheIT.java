package com.skybook.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.redis.testcontainers.RedisContainer;
import com.skybook.inventory.domain.CabinClass;

import tools.jackson.databind.json.JsonMapper;

/** The seat-map cache against a real Redis: serialisation, TTL and eviction. */
@Testcontainers(disabledWithoutDocker = true)
class RedisSeatMapCacheIT {

    @Container
    static final RedisContainer REDIS = new RedisContainer(DockerImageName.parse("redis:8.10.3-alpine"));

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redis;
    private RedisSeatMapCache cache;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getFirstMappedPort());
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redis = new StringRedisTemplate(connectionFactory);
        cache = new RedisSeatMapCache(redis, JsonMapper.builder().build(), Duration.ofSeconds(30), Clock.systemUTC());
    }

    @AfterEach
    void tearDown() {
        connectionFactory.destroy();
    }

    @Test
    void storesWithTtlAndEvicts() {
        SeatMapSnapshot snapshot = new SeatMapSnapshot(42L, "USD",
                List.of(new SeatMapSnapshot.SeatView("12C", CabinClass.ECONOMY, true, new BigDecimal("110.00"))));

        cache.put(snapshot);

        assertThat(cache.get(42L)).contains(snapshot);
        assertThat(redis.getExpire(RedisSeatMapCache.KEY_PREFIX + 42)).isBetween(1L, 30L);

        cache.evict(42L);
        assertThat(cache.get(42L)).isEmpty();
    }
}
