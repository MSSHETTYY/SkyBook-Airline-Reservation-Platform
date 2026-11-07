# ADR-0007: Idempotency keys stored with the business record

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

Networks drop responses. A mobile client that times out on "create booking" will retry, and a
service calling inventory will retry on a 503. Without protection, a retry creates a second booking,
a second seat hold or a second charge. Every `POST` that creates something therefore requires an
`Idempotency-Key` header.

## Decision

- **Store the key in the same row and transaction as the record it creates**, guarded by a unique
  constraint: `booking (customer_id, idempotency_key)`, `seat_hold (idempotency_key)`,
  `flight (creation_key)`, and later `payment (idempotency_key)`. Store a **fingerprint** of the
  request next to it.
- **Same key and same fingerprint:** return the original result (replay). The status code and body
  are the same as the first time.
- **Same key and different fingerprint:** `422 IDEMPOTENCY_KEY_REUSED`.
- **Two concurrent requests with the same key:** both pass the "already exists?" check, and the
  unique constraint picks a winner. The loser catches the violation, re-reads the winner's record
  and replays it.
- **Keys are scoped:** customer keys are per customer. booking-service derives inventory's
  (global) key as `bk-` plus a SHA-256 hash of the customer id and the client key. A retried
  booking request therefore reuses the *same* seat hold instead of creating a second one.
- **Operations without a key are made idempotent by design:** confirming a hold with the same
  reference, releasing an already-released hold, releasing seats that are already free.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Generic idempotency table plus a servlet filter that caches responses | One implementation for every endpoint | Not atomic with the business write: a crash between commit and caching leaves a record without a key, and the retry duplicates it. Also caches error responses. |
| Redis-based key store | Fast, has TTLs | Not transactional with the database; Redis loss means duplicates |
| No keys; rely on natural uniqueness | No client burden | There is no natural key for "this customer's second booking on the same flight" |

## Consequences

- Duplicates are impossible even across crashes, because the key commits atomically with the data.
- Each resource needs a few lines of replay logic (`replay(existing, fingerprint)`). This is
  explicit and tested per use case.
- Keys are retained as long as the records. A clean-up policy is not needed at this scale.
