# ADR-0015: Resilience patterns for partner calls, and consumer-driven contracts

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

booking-service calls inventory, payment-service, the GDS, the CRM and the loyalty system; payment-service
calls Stripe. Each can be slow, fail intermittently or be down. Separately, the services evolve
independently, and a provider change that breaks a consumer should fail a build, not production.

## Decision

### Resilience (Resilience4j), per partner

Every call goes through `RemoteCall` (booking) or `ResilientPaymentProvider` (payment). The decorators
are layered retry → circuit breaker → bulkhead → call, with explicit HTTP timeouts:

| Partner | Timeout | Retry | Bulkhead | On exhaustion |
|---------|---------|-------|----------|---------------|
| inventory | 3 s | 3x exponential (200 ms) | – | 503 to the client; the hold expires on its own |
| payment-service | 5 s | 3x exponential (300 ms) | 25 | booking still created, payment attached on retry |
| GDS | 4 s | 3x exponential (500 ms) | 10 | saga retries for 10 minutes, then compensates |
| CRM | 1 s | 2x (100 ms) | 20, no waiting | **fallback** to STANDARD tier |
| loyalty | 1 s | 2x (200 ms) | 5, no waiting | **fallback**: log and reconcile later |
| Stripe | 2 s connect, 10 s read | 3x exponential (300 ms), SDK retries off | 20 | 503 problem; webhooks complete payments asynchronously |

- **Only transient failures are retried** (I/O errors, timeouts, 5xx, 429), and only they count towards
  the circuit breaker. A 4xx is a business answer: it is never retried and keeps its error code (for
  example `SEAT_UNAVAILABLE`).
- **Retrying is only allowed because every mutating call is idempotent** (idempotency keys, ADR-0007).
- **Fault injection** is built into the mock partners: flight SB999 makes the GDS fail and SB998 makes it
  slow (`mock-partners/README.md`). The adapter tests run against those real mappings.

### Consumer-driven contracts (Pact)

- booking-service's adapter tests define what it needs from payment-service and inventory-service and
  write the contracts to `contracts/pacts/` (committed).
- payment-service and inventory-service verify those files against their running applications in their
  own builds, with provider states that create the needed data (for example a flight whose generated id
  is injected into the request path).
- Contracts describe the business exchange only. The provider tests add credentials (a SERVICE token).

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Spring Cloud CircuitBreaker abstraction everywhere | Vendor-neutral | Loses bulkhead/retry composition control; Resilience4j is used directly, but the gateway uses the Spring Cloud abstraction where it is idiomatic |
| Annotation-based Resilience4j (`@CircuitBreaker`) | Less code | AOP proxies hide the order of decorators; harder to unit-test |
| Pact Broker | Versioned contracts, can-i-deploy checks | Another service to run; the folder approach works in a monorepo. A broker is the next step if the services move to separate repositories |
| OpenAPI-only compatibility checks | Already have specs | The spec says what the provider *offers*, not what consumers *use*; Pact tests the latter |

## Consequences

- A partner outage degrades one capability (ticketing waits, tiers default, miles are delayed) instead
  of failing bookings.
- Thread pools stay healthy under partner slowness, thanks to the bulkheads and timeouts.
- Changing a response field that booking-service reads fails payment-service's build
  (`PaymentProviderContractTest`).
