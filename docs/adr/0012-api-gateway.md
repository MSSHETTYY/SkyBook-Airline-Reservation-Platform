# ADR-0012: Spring Cloud Gateway as the single edge

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

The SPA and external callers need one entry point. Cross-cutting edge concerns (authentication,
throttling, CORS, correlation, failure isolation) should be implemented once, and internal APIs must
not be reachable from the internet.

## Decision

Spring Cloud Gateway (reactive, Netty) with:

- **An explicit route allow-list.** Only public operations are routed. Inventory's internal holds and
  seat-release APIs are deliberately *not* routed, so a stolen SERVICE token is useless from outside.
  A test proves that `POST /api/v1/flights/{id}/holds` returns 404 at the edge.
- **JWT validation at the edge** (Keycloak issuer). Public: flight search and seat maps, the Stripe
  webhook (authenticated by its signature), and health checks. Everything else requires a valid token.
  The services validate the token again (ADR-0008).
- **Correlation IDs** are assigned in a `WebFilter` that runs before security, so even 401 and 429
  responses carry `X-Correlation-Id`.
- **Rate limiting** (`RequestRateLimiter`): **per user** when authenticated, **per IP** when anonymous.
  It uses `RedisRateLimiter` (token buckets shared by all gateway replicas, 20 req/s with a burst of 40),
  which **fails open** if Redis is down: availability over strict limiting. `LocalRateLimiter` (an
  in-memory token bucket) exists for single-instance local runs and tests.
- **A circuit breaker per downstream service** (Spring Cloud CircuitBreaker on Resilience4j). Down
  services, open circuits and 502/503/504 responses return a fast RFC 9457
  `503 SERVICE_UNAVAILABLE` problem with `Retry-After`.
- **CORS** allows only the SPA origin(s), with an explicit list of headers (`Authorization`,
  `Idempotency-Key`, `X-Correlation-Id`).
- **Hygiene:** cookies are stripped before forwarding (the APIs are token-based), and connect and
  response timeouts are bounded.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Kong / NGINX / Envoy | Very fast; rich plugin ecosystems | Another technology and configuration language; less natural to unit-test with the Java stack |
| Ingress controller only | Fewer components | No per-user rate limits, JWT-aware routing or circuit breaking |
| No gateway; SPA calls services directly | Simplest | CORS, authentication and throttling duplicated everywhere; internal APIs exposed |

## Consequences

- `GatewayEdgeTest` covers routing, the internal-API block, JWT rejection, token relay, cookie
  stripping, per-user throttling, CORS and fallbacks. `RedisRateLimiterIT` covers the distributed
  limiter (needs Docker).
- Rate-limited responses use the gateway's standard `429` with `X-RateLimit-*` headers, not a
  problem body. That is accepted for now. The SPA treats 429 generically.
