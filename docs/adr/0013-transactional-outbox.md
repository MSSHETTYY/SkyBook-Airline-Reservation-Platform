# ADR-0013: Transactional outbox, idempotent consumers and per-consumer dead-letter topics

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

Services must change their database and tell other services about it. Writing to the database and then
publishing to Kafka is a dual write: a crash between the two loses the event (the booking is confirmed but
no confirmation e-mail is ever sent), and publishing first can announce a change that then rolls back.
Kafka itself delivers at least once, so consumers will sometimes see the same event twice.

## Decision

The shared `libs/messaging` library implements three parts.

1. **Transactional outbox** (`OutboxWriter`). Events are inserted into the service's `outbox_event` table
   *in the same transaction* as the business change. The writer refuses to run outside a transaction, so
   the guarantee cannot be lost by accident.
2. **Polling relay** (`OutboxRelay`). Every 500 ms the relay claims a batch of unpublished rows, oldest
   first, with `FOR UPDATE SKIP LOCKED`. It sends each row to Kafka (idempotent producer, `acks=all`) and
   marks it published only after the broker acknowledges it.
   - A batch stops at the first failure, so a later event of an aggregate never overtakes an earlier one.
   - The Kafka key is the aggregate id (the booking reference), so a booking's events stay in order
     within one partition.
   - Published rows are deleted after 7 days.
3. **Idempotent consumers** (`ProcessedEventStore`). A consumer records `(consumer, event id)` in the same
   transaction as its state change. Redeliveries hit the primary key and are skipped. A failure rolls
   back both the change and the marker, so the retry starts cleanly. Stripe webhooks use the same store,
   keyed by Stripe event id.
4. **Error handling.** A `DefaultErrorHandler` retries with exponential backoff, then publishes the record
   to `<topic>.<consumer-group>.DLT` on the same partition. Records that can never succeed (malformed
   JSON, missing fields) skip the retries.
   - The DLT name includes the consumer group because several services consume the same topic, and an
     operator replaying a record must know which consumer failed.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Debezium CDC on the outbox table | No polling; lower latency; the relay is not in the service | Kafka Connect plus Debezium is about 1 GB more RAM on an 8 GB laptop and another system to run; a reasonable upgrade path later because the table format stays the same |
| Kafka transactions (exactly-once) | Atomic produce and offset commit | Does not cover the *database* write; that dual write remains |
| Listen-to-yourself (publish, then update state from your own event) | Single write | The state change is delayed and complex; reads after writes are inconsistent |
| Consumer de-duplication in Redis | Fast | Not transactional with the consumer's database change |

## Consequences

- Delivery is at least once end to end. Correctness comes from idempotency at every consumer, which
  `SagaOverKafkaTest` proves by redelivering an event.
- Publish latency is up to one poll interval (500 ms). That is acceptable for booking workflows.
- Each service owns its `outbox_event` and `processed_event` tables in its own Flyway history. The DDL in
  `libs/messaging` is a reference only, because a shared migration would interleave versions across
  services.
- Operations: monitor the age of unpublished rows and the DLT depth. The runbook describes how to replay
  a DLT record.
