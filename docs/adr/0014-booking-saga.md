# ADR-0014: Orchestrated booking saga with persisted state and a recovery job

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

A booking crosses four systems: inventory (seats), payment-service and Stripe (money), the GDS (tickets),
and loyalty (miles). There is no distributed transaction across them. Partners are slow or down at times,
messages can be redelivered, and a pod can die between any two steps. Customers must never pay without
getting a ticket or a refund, and seats must never stay blocked by a dead booking.

## Decision

**Orchestration in booking-service** (`BookingSagaService`), driven by the booking's persisted status:

| Status | Next step(s) | On failure |
|--------|--------------|------------|
| PENDING_PAYMENT | the customer pays (Stripe Elements); the PaymentCompleted event moves the booking to PAID | the hold expires: EXPIRED, hold released, payment cancelled |
| PAID | issue tickets (GDS), then confirm the hold (inventory): CONFIRMED + TicketIssued, then loyalty miles (best effort) | transient: retried by the recovery job for up to 10 minutes. Permanent (seats lost, GDS rejection) or past the deadline: void tickets, release hold, **full refund**, then FAILED + BookingCancelled |
| CANCELLATION_PENDING | void tickets, release seats, refund the amount fixed at request time per the fare rules; CANCELLED once the RefundIssued events cover it | each step is idempotent and retried |
| CHANGE_PENDING_PAYMENT | once paid: issue new tickets, confirm the new hold, then CONFIRMED + BookingChanged, release old seats, void old tickets | change no longer possible: refund the difference and keep the original flight. Unpaid when the hold ends: abandon the change |

- **Every step is idempotent:**
  - GDS requests carry an idempotency key per booking and flight;
  - inventory confirm and release are idempotent;
  - payment calls carry idempotency keys derived from the booking reference;
  - state changes are guarded by optimistic locking.
- **Two-phase event handling:**
  1. The Kafka listener records the payment (local state change plus the de-duplication marker, in one
     transaction).
  2. It then calls `resume(reference)`, which executes the remote steps outside any transaction.
  `resume` also runs for a duplicate event, so a crash between the phases heals on redelivery.
- **The recovery job** (`SagaRecoveryJob`, every 30 s) resumes any booking stuck in PAID,
  CANCELLATION_PENDING or CHANGE_PENDING_PAYMENT for more than 30 s. That covers crashes, partner
  outages and lost messages. It is safe on every replica.
- **Graceful degradation at booking creation:** if payment-service is down, the booking is still created
  and its seats stay held. Retrying the same request (same `Idempotency-Key`) attaches the payment.
- **Change fees** depend on the CRM loyalty tier, with a fail-safe fallback to STANDARD. The refund rules
  are explicit domain policies (`RefundPolicy`, `ChangeFeePolicy`).

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Choreography (services react to each other's events) | No central coordinator | The flow is scattered; timeouts and compensation are hard to see and test; "where is booking X stuck?" has no single answer |
| Workflow engine (Temporal, Camunda) | Durable timers, visual history | Another cluster to run (memory budget); overkill for four steps whose state fits in the booking row |
| Synchronous call chain in the HTTP request | Simple | The customer waits on the GDS; any partner outage fails the payment confirmation |

## Consequences

- The booking row *is* the saga log: its status, `updated_at`, pending change and amounts tell exactly
  where each saga is. Agents see it in the admin API.
- Compensation can itself fail (payment-service down). It is then retried by the recovery job because
  the status has not moved, which `failedRefundDuringCompensationLeavesTheSagaForRecovery` proves.
- Best-effort steps (voiding tickets, crediting miles, releasing old seats after a change) log a warning
  instead of blocking the customer. Reconciliation of those is an operational task in the runbook.
- 19 saga tests cover every path with real domain objects, and `SagaOverKafkaTest` runs the happy path
  over a real Kafka broker.
