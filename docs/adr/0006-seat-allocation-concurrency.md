# ADR-0006: Zero overbooking with optimistic locking and expiring seat holds

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

Popular flights receive many simultaneous requests for the same seats. A seat must never be
allocated twice. Customers need time to pay (up to 15 minutes), so a seat must be reserved during
checkout without blocking it forever when they abandon checkout.

## Decision

1. **Seat rows are the unit of concurrency.** Each seat (`seat` table) has a `version` column. Every
   state change (`AVAILABLE -> HELD -> BOOKED`, and back on release) is a JPA update that becomes
   `UPDATE seat ... WHERE id = ? AND version = ?`.
2. **When two transactions race for a seat**, the database serialises the row updates. The second
   one finds the version already incremented, updates zero rows, and Hibernate raises an
   optimistic-locking failure. The service flushes inside the transaction, catches the failure and
   reports `409 SEAT_UNAVAILABLE`. The whole multi-seat hold rolls back, so partial holds never
   happen.
3. **Deadlock avoidance:** `hibernate.order_updates=true` flushes updates in primary-key order, so
   concurrent multi-seat holds lock rows in the same order.
4. **Holds expire after 15 minutes.** An expired hold counts as available immediately (the
   `isAvailableAt(now)` check), even before the sweeper runs. The sweeper (`HoldExpiryJob`) only tidies
   state and refreshes the read model. It runs safely on every replica, because each hold is expired
   in its own optimistic transaction.
5. **A late payment is honoured when possible.** Confirming an expired hold still succeeds if nobody
   else took the seats. If they were taken, it fails with `HOLD_EXPIRED` and the saga refunds the
   customer.
6. **The Redis read model is never consulted for allocation.** It serves search and seat maps only.
7. **The proof** is `AbstractNoOverbookingTest`. Many threads race for one seat (exactly one wins),
   and many overlapping three-seat holds compete for ten seats (no seat appears in two winning
   holds, and the database agrees with the API responses). It runs against H2 on every build and
   against Oracle Free with Testcontainers (`NoOverbookingOracleIT`).

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Pessimistic locking (`SELECT ... FOR UPDATE`) | Losers wait instead of failing | Holds row locks across the read-check-write; under contention threads queue on hot seats; deadlock risk with multi-seat holds |
| Atomic conditional update (`UPDATE seat SET status='HELD' WHERE id IN (...) AND status='AVAILABLE'`, then check the row count) | One round trip, no version column | Bypasses the domain model; expiry semantics (held-but-expired counts as available) end up in SQL |
| Redis lock or Redis seat counter | Very fast | A second source of truth; a Redis failover can lose locks, which means overbooking |
| `SERIALIZABLE` isolation | Simple reasoning | Oracle's serializable mode raises `ORA-08177` under contention; throughput drops |

## Consequences and trade-offs

- **The losers fail fast with a clear 409.** The client shows the updated seat map and the customer
  picks another seat. That is the correct experience for seat selection.
- **Under extreme contention for one seat**, most requests fail. That is inherent to the problem,
  not a weakness of the mechanism.
- **A hold reserves seats for up to 15 minutes**, even if the customer abandons checkout. That cost
  is accepted for a smooth checkout. The duration is configurable (`skybook.inventory.hold-ttl`).
- **The cache-aside seat map can show a seat as available for up to the cache TTL (30 s)** after it
  was taken. Trying to hold it returns 409 and evicts the stale entry.
