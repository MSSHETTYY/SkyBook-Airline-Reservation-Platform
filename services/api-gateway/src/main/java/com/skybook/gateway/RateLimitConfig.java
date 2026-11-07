package com.skybook.gateway;

import java.net.InetSocketAddress;
import java.security.Principal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import reactor.core.publisher.Mono;

/**
 * Rate-limit keys: authenticated callers are limited per user (token subject), anonymous traffic per
 * client IP. Limiting per user rather than per IP keeps many customers behind one corporate NAT from
 * throttling each other, while anonymous search is still protected against scraping.
 */
@Configuration(proxyBeanMethods = false)
class RateLimitConfig {

    @Bean
    @Primary
    KeyResolver principalOrIpKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(subject -> "user:" + subject)
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
                    return "ip:" + (remote == null ? "unknown" : remote.getAddress().getHostAddress());
                }));
    }

    /** Only when selected; primary so it becomes the gateway's default limiter instead of Redis. */
    @Bean
    @Primary
    @ConditionalOnProperty(name = "skybook.gateway.rate-limiter-bean", havingValue = "localRateLimiter")
    LocalRateLimiter localRateLimiter(GatewayProperties properties) {
        return new LocalRateLimiter(properties.localReplenishRate(), properties.localBurstCapacity());
    }
}
