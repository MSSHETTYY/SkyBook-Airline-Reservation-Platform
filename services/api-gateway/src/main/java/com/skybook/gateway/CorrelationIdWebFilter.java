package com.skybook.gateway;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import com.skybook.common.web.CorrelationId;

import reactor.core.publisher.Mono;

/**
 * Assigns the correlation ID at the edge. Runs before security, so even 401/429 responses carry it.
 * A valid client-supplied ID is kept (lets a client trace its own retries); anything else is replaced.
 * The ID is forwarded to the downstream service and echoed to the client.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class CorrelationIdWebFilter implements WebFilter {

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String correlationId = CorrelationId.acceptOrGenerate(
                exchange.getRequest().getHeaders().getFirst(CorrelationId.HEADER));
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(CorrelationId.HEADER, correlationId))
                .build();
        exchange.getResponse().getHeaders().set(CorrelationId.HEADER, correlationId);
        return chain.filter(exchange.mutate().request(request).build());
    }
}
