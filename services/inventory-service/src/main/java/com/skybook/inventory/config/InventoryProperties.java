package com.skybook.inventory.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tunables for inventory-service ({@code skybook.inventory.*}).
 *
 * @param holdTtl             how long a seat hold lasts before it expires
 * @param expiryBatchSize     maximum holds expired per sweep, to bound transaction size
 * @param seatMapCacheTtl     safety-net TTL of cached seat maps (they are also evicted on every change)
 * @param cacheEnabled        whether seat maps are cached in Redis
 */
@ConfigurationProperties("skybook.inventory")
public record InventoryProperties(
        Duration holdTtl,
        int expiryBatchSize,
        Duration seatMapCacheTtl,
        boolean cacheEnabled) {

    public InventoryProperties {
        holdTtl = holdTtl == null ? Duration.ofMinutes(15) : holdTtl;
        expiryBatchSize = expiryBatchSize <= 0 ? 100 : expiryBatchSize;
        seatMapCacheTtl = seatMapCacheTtl == null ? Duration.ofSeconds(30) : seatMapCacheTtl;
    }
}
