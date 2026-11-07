package com.skybook.inventory.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.skybook.inventory.domain.PricingPolicy;
import com.skybook.inventory.service.RedisSeatMapCache;
import com.skybook.inventory.service.SeatMapCache;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class InventoryConfig {

    @Bean
    PricingPolicy pricingPolicy() {
        return new PricingPolicy();
    }

    @Bean
    SeatMapCache seatMapCache(InventoryProperties properties, StringRedisTemplate redis, ObjectMapper objectMapper,
                              Clock clock) {
        return properties.cacheEnabled()
                ? new RedisSeatMapCache(redis, objectMapper, properties.seatMapCacheTtl(), clock)
                : SeatMapCache.NONE;
    }

    /** Scheduled jobs are switched off in tests, which drive them explicitly with a controlled clock. */
    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "skybook.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class SchedulingConfig {
    }
}
