package com.skybook.gateway;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Edge settings ({@code skybook.gateway.*}).
 *
 * @param allowedOrigins     browser origins allowed by CORS (the SPA)
 * @param rateLimiterBean    bean used by the RequestRateLimiter filter: {@code redisRateLimiter}
 *                           (distributed, production) or {@code localRateLimiter} (single instance)
 * @param localReplenishRate tokens per second for the local rate limiter
 * @param localBurstCapacity bucket size for the local rate limiter
 */
@ConfigurationProperties("skybook.gateway")
public record GatewayProperties(List<String> allowedOrigins, String rateLimiterBean, int localReplenishRate,
                                int localBurstCapacity) {

    public GatewayProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
        localReplenishRate = localReplenishRate <= 0 ? 20 : localReplenishRate;
        localBurstCapacity = localBurstCapacity <= 0 ? 40 : localBurstCapacity;
    }
}
