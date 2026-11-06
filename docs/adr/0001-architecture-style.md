# ADR-0001: Architecture style - event-driven microservices with an orchestrated booking saga

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** SkyBook architecture

## Context

SkyBook sells seats on flights. The capabilities have very different characteristics:

| Capability    | Load profile                                   | Consistency need                         | Change rate |
|---------------|------------------------------------------------|------------------------------------------|-------------|
| Inventory     | Read-heavy (search ~100x bookings), spiky      | Strong for seat allocation, eventual for search | Low   |
| Booking       | Write-heavy, long-running (hold → pay → ticket) | Strong per booking                       | High        |
| Payment       | Low volume, PCI-sensitive                      | Exactly-once effect on money             | Low         |
| Notification  | Bursty, latency-tolerant                       | At-least-once                            | Medium      |

A booking spans inventory, payment and an external GDS, none of which can share a database
transaction. Partners (GDS, CRM, loyalty) are slow and unreliable. The system must never overbook a
seat and never charge twice.

## Decision

1. **Services by business capability**, each owning its data (database per service):
   `inventory-service` (Oracle + Redis read model), `booking-service` (MariaDB),
   `payment-service` (MariaDB), `notification-service` (MongoDB), behind an `api-gateway`.
2. **Synchronous REST** (OpenAPI-first, `/api/v1`) for queries and for commands where the caller
   needs an immediate answer (search, hold seats, create payment intent).
3. **Asynchronous domain events on Kafka** for state changes other services react to
   (`BookingCreated`, `SeatsHeld`, `PaymentCompleted`, ...). Events are published via a
   **transactional outbox** so a state change and its event are atomic; consumers are idempotent.
4. **An orchestrated saga in booking-service** coordinates hold → pay → ticket → confirm and the
   compensations (release seats, refund). Orchestration over choreography because the flow has
   branches, timeouts and compensations that must be visible and testable in one place.
5. **Hexagonal architecture for booking-service** (the domain with the most rules and the most
   integrations); a plain layered structure for the simpler services. ArchUnit enforces both.
6. **Shared code is limited to cross-cutting technical concerns** (`libs/common`: error model,
   correlation IDs, event envelope). No shared domain model - services share contracts (OpenAPI,
   event schemas), not classes.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Modular monolith | One deployable, ACID across modules, far cheaper to run and debug | Cannot scale search independently of booking; one PCI scope for everything; does not exercise the distributed-systems concerns this platform must demonstrate |
| Choreographed saga (pure event reactions) | No central coordinator, loose coupling | Flow logic scattered across services; hard to reason about timeouts and compensations; difficult to answer "where is booking X stuck?" |
| Two-phase commit / XA | Atomicity across resources | Not supported by Kafka or Stripe; blocks on coordinator failure; poor availability |
| Shared database | Simple joins and transactions | Couples schemas and release cycles; defeats independent scaling and ownership |

**Honest trade-off:** for a team of this size a modular monolith would be the cheaper choice in
production. Microservices are chosen because independent scaling of search, isolation of the PCI
scope and partner fault isolation are explicit requirements, and to make the distributed
concerns (sagas, outbox, idempotency, resilience) first-class and testable.

## Consequences

- **Positive:** inventory search scales independently and is served from Redis; payment is
  isolated for PCI scope (see the PCI-DSS note); a GDS outage degrades ticketing only.
- **Negative:** eventual consistency between services; more moving parts to operate; local
  development needs Docker profiles (ADR-0003); debugging needs correlation IDs and tracing,
  which therefore are built in from Phase 0 (`CorrelationIdFilter`, `EventEnvelope.correlationId`).
- **Risks and mitigations:**
  - *Lost or duplicated events* → transactional outbox + idempotent consumers keyed on `eventId`.
  - *Overbooking under concurrency* → seat allocation is decided only by inventory-service against
    its source-of-truth database with optimistic locking; the Redis read model is never trusted
    for allocation.
  - *Saga stuck mid-flow* → persisted saga state with timeouts and compensations; seat holds expire
    after 15 minutes regardless of saga state.
