# ADR-0005: Hexagonal architecture for booking-service, layered for the others

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

booking-service has the richest rules of the platform: a status machine with eight states, refunds
that may never exceed payments, date changes with fare differences, and a saga coordinating four
external parties (inventory, payment, GDS, loyalty). Every one of those parties can be slow, fail,
or be replaced (Stripe for another provider, the WireMock GDS for a real one). inventory-service and
payment-service, by contrast, are mostly persistence with a few rules.

## Decision

**booking-service uses ports and adapters (hexagonal architecture):**

```
adapter/in/web, adapter/in/scheduling, (adapter/in/messaging)  -> driving adapters
application/port/in   use cases (CreateBookingUseCase, BookingQueries, ...)
application/service   orchestration, idempotency, compensation
application/port/out  BookingRepository, InventoryPort, (PaymentPort, TicketingPort, ...)
domain/model          Booking aggregate, value objects - plain Java
adapter/out/...       JPA, REST clients - driven adapters
config                composition root
```

- The domain has **no framework dependencies**: no JPA annotations, no Spring, no Jackson. It is
  persisted through an immutable `BookingState` record, which the persistence adapter maps to
  separate JPA entities.
- The application core may use Spring's `@Service` and the technology-neutral
  `DataAccessException` hierarchy, but no persistence, web or HTTP-client types.
- Generated OpenAPI DTOs live in the web adapter. The inventory client DTOs (generated from the
  inventory spec) live in the inventory adapter.
- **ArchUnit enforces all of this** (`BookingArchitectureTest`).

**inventory-service and payment-service use a classic layered structure** (web, service,
repository, domain), also enforced by ArchUnit. JPA annotations on their entities are accepted,
because the extra mapping layer would cost more than it returns there.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Layered everywhere | One style, less code | booking's rules would mix with JPA and HTTP details; swapping the GDS or payment provider would touch business code |
| Hexagonal everywhere | One style | Double mapping (domain to entity) with little benefit in CRUD-heavy services |
| JPA annotations on the booking aggregate | No mapping code | The domain depends on persistence; lazy-loading and proxy rules leak into the business code |

## Consequences

- Business rules are unit-tested without Spring: `BookingTest` and `BookingServiceTest` (mocked
  ports) run in milliseconds and cover most of the logic.
- There is more code: mapper classes and the `BookingState` record. Passengers are updated in place
  by position so that rows are never deleted and re-inserted.
- Replacing an external system means adding an adapter. The core does not change.
