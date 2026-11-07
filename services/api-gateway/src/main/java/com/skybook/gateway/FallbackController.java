package com.skybook.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Target of the routes' circuit-breaker fallback: a fast, well-formed 503 instead of a hanging request
 * or a raw connection error when a downstream service is down or its circuit is open.
 */
@RestController
class FallbackController {

    @RequestMapping("/fallback/unavailable")
    Mono<Void> unavailable(ServerWebExchange exchange) {
        exchange.getResponse().getHeaders().set("Retry-After", "10");
        return ProblemResponses.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE",
                "The service is temporarily unavailable. Please try again shortly.");
    }
}
