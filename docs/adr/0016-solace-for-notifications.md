# ADR-0016: Kafka for domain events, Solace PubSub+ for notification delivery

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

Domain events (BookingCreated, TicketIssued, RefundIssued...) are the integration backbone between
services. Kafka is a good fit: an ordered, replayable log, partitioned by booking for per-aggregate
ordering. Notification delivery has different needs:

- **Fan-out to channel consumers** that come and go (e-mail today; SMS, push, partner webhooks later),
  each interested in a different subset of events;
- **Per-message guaranteed delivery** with redelivery limits and a dead-message queue, rather than
  per-partition offsets where one stuck message blocks the partition;
- **Standard APIs** (JMS) that enterprise teams and many airline systems already use;
- **Fine-grained routing by subject** (event type, booking) without new code.

## Decision

- Kafka stays the system of record for domain events (ADR-0013).
- A small **event-bridge** consumes `skybook.booking.events` and `skybook.payment.events` and republishes
  each event, persistently, to a **hierarchical Solace topic**: `skybook/<domain>/<EventType>/<reference>`.
- **notification-service** consumes a **durable Solace queue** (`q.notification`) subscribed to
  `skybook/>`, through the **standard JMS API**.
  - Its listener runs in a transacted session: the message is acknowledged only after the e-mail went
    out and was audited.
  - After 3 failed deliveries the broker moves the message to `q.notification.dmq`.
- New channels add a queue with their own topic subscription (for example
  `skybook/booking/TicketIssued/>` for an SMS service). Neither the bridge nor Kafka changes.
- **Idempotency end to end:**
  - the bridge is at least once (it commits the Kafka offset after a successful send);
  - notification-service de-duplicates by event id in its MongoDB audit trail;
  - payment events carry no personal data, so notification-service keeps a local projection
    "booking reference → contact e-mail", built from booking events.
- Tests run the JMS code against an embedded Artemis broker (same JMS API), GreenMail for SMTP and an
  embedded Kafka broker. `infra/solace/provision.sh` creates the queue, subscription and DMQ on the real
  broker through SEMPv2.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| notification-service consumes Kafka directly | One broker, less to run | Per-partition blocking on a bad message, offset-based retries, no topic-wildcard routing; every new channel reimplements filtering |
| Solace's built-in Kafka bridge (broker feature) | No bridge code | Configuration lives only in the broker; harder to test and version; the code bridge is about 60 lines and unit-tested |
| RabbitMQ instead of Solace | Popular, light | Not in the brief; Solace's hierarchical topics plus durable queues with topic subscriptions match the routing needs closely |
| Only Solace, no Kafka | One broker | Loses Kafka's replayable log and ordered partitions for domain integration |

## Consequences

- One more component (event-bridge) and one more broker to operate. Memory is managed by compose
  profiles (ADR-0003).
- Duplicate e-mails are possible in one narrow case: an e-mail is sent but the audit write fails, and
  the message is redelivered. That is accepted and documented, because "at least once" is the right
  default for transactional e-mail.
- Operators watch `q.notification.dmq`. The runbook covers inspecting and re-queuing dead messages.
