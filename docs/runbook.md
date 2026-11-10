# SkyBook runbook

For the engineer on call. Every alert links to a section here (`runbook_url`). Commands assume the Helm
release `skybook` in namespace `skybook`; for the local stack use the `docker compose` equivalents.

**First five minutes, whatever the alert:**
1. Open Grafana → *SkyBook - service overview and SLOs*. Find which service, since when, and how much
   error budget is left.
2. Check what changed:
   - `helm history skybook -n skybook`
   - the latest `deploy` workflow run
   - the infrastructure status pages
3. Grab a `correlationId` from an error (problem JSON or logs). Find its trace in Jaeger (Grafana → Loki →
   *View trace*).
4. If a deploy in the last hour lines up with the start of the errors, **roll back first, investigate
   second**: `helm rollback skybook -n skybook`.

---

## High error rate

*Alerts:* `SkyBookErrorBudgetFastBurn` (page), `SkyBookErrorBudgetSlowBurn` (ticket).

1. Grafana *5xx ratio by service* shows which service is failing. The gateway's own 503s with code
   `SERVICE_UNAVAILABLE` mean a downstream circuit breaker or route fallback opened.
2. Look at the logs of the failing service: `{service="booking-service", level="ERROR"}` in Loki, or
   `kubectl -n skybook logs deploy/booking-service --since=15m`.
3. Typical causes and fixes:
   - **Database unavailable / pool exhausted** (`SQLTransientConnectionException`): check the database
     health and connection limits; scale down misbehaving clients.
   - **Downstream service down:** follow [Service down](#service-down) for that service.
   - **Bad release:** `helm rollback skybook -n skybook` (or redeploy the previous image tag with the
     `deploy` workflow).
4. When the ratio is back under 0.5%, record the incident in a post-mortem if it used more than 20% of the
   error budget ([policy](slo.md#error-budget-policy)).

## Slow flight search

*Alert:* `SkyBookFlightSearchSlow` (p95 > 300 ms for 10 minutes).

1. Redis is the read model for seat maps (ADR-0006). If the Redis panels or the logs show connection errors,
   every search falls back to Oracle and gets slower: restore Redis and searches recover on their own
   (cache-aside, no warm-up needed).
2. Oracle: look for long-running queries and lock waits on `SEAT`. A flood of holds on one flight can
   contend on optimistic locks; the `409 SEAT_UNAVAILABLE` rate rises at the same time.
3. Saturation: if CPU is high on inventory-service, check that the HPA is scaling
   (`kubectl -n skybook get hpa inventory-service`) and that it is not at `maxReplicas`.

## Circuit breaker open

*Alert:* `SkyBookCircuitBreakerOpen` (names: `inventory`, `payment`, `gds`, `crm`, `loyalty`).

| Breaker | Customer impact while open | What to do |
|---------|----------------------------|------------|
| `inventory` | New bookings fail fast with 503 | Treat as [Service down](#service-down) for inventory-service |
| `payment` | New bookings are created without a payment; payment is attached when payment-service recovers | Restore payment-service; nothing to replay |
| `gds` | Paid bookings wait in PAID; after 10 minutes the saga refunds and marks them FAILED | Contact the GDS provider; once recovered, the recovery job tickets bookings still within the deadline |
| `crm` | Customers are treated as tier STANDARD (change fees not waived) | Non-urgent; goodwill refunds via servicing if customers complain |
| `loyalty` | Miles accrual is delayed | Non-urgent |

The breakers close by themselves after a successful half-open probe. Do not restart services to "reset"
them.

## Service down

*Alert:* `SkyBookServiceDown`.

1. `kubectl -n skybook get pods -l app.kubernetes.io/name=<service>`. Look for `CrashLoopBackOff`,
   `OOMKilled` or `Pending`.
2. `kubectl -n skybook describe pod <pod>` shows events: failing probes, image pull errors, unschedulable
   pods.
3. Start-up failures: `kubectl -n skybook logs <pod> --previous`. The usual suspects are:
   - unreachable database;
   - wrong secret in Vault;
   - an expired certificate (cert-manager:
     `kubectl -n skybook get certificate`, `kubectl describe certificate <service>`).
4. `OOMKilled`: see [Memory pressure](#memory-pressure).

## Memory pressure

*Alert:* `SkyBookJvmHeapPressure` (heap > 90% for 10 minutes).

1. Check the trend: a steady climb that never drops after GC suggests a leak; a plateau suggests the limit is
   too low for the load.
2. Capture evidence before restarting. The runtime image is a JRE without `jcmd`, so read the metrics from
   the management port:
   `kubectl -n skybook port-forward <pod> 9090`, then open
   `http://localhost:9090/actuator/metrics/jvm.memory.used?tag=area:heap` and
   `.../jvm.gc.pause`.
3. Short term: scale out (`kubectl -n skybook scale deploy/<service> --replicas=N` when the HPA is off) or
   raise the memory limit in the values file. Heap is 70% of the container limit.

## Kafka outage

*Symptoms:* bookings stay in PAID or PENDING_PAYMENT after payment, `last_error` rises in the outbox tables,
event-bridge logs show disconnects.

1. **No data is lost:** every state change is in the service's `outbox_event` table (ADR-0013), and the
   relays retry every 0.5 s in order.
2. Measure the backlog:
   `select count(*), min(created_at) from outbox_event where published_at is null;`
   in the booking and payment databases.
3. Restore the broker. The relays drain the backlog automatically, the saga resumes and recovery jobs pick up
   stalled bookings within 30 s.
4. Verified by the chaos test `OutboxRelayChaosIT` and the game-day script `tests/chaos/kafka-outage.sh`.

## Dead letters

*Kafka:* a record that failed `consumerRetries` times goes to `<topic>.<consumer-group>.DLT`, for example
`skybook.payment.events.booking-service.DLT`.

1. Inspect it:
   `kafka-console-consumer --bootstrap-server <broker> --topic <dlt> --from-beginning --property print.headers=true`.
   The headers carry the exception and the original offset.
2. Fix the cause (usually a bug or an unexpected payload), deploy, then re-publish the record to the
   original topic. Consumers are idempotent (`processed_event`), so re-driving a record twice is safe.

*Solace:* undeliverable notifications move to `q.notification.dmq` after 3 attempts. Re-drive them from
PubSub+ Manager (copy to `q.notification`) once SMTP or the template issue is fixed.

## Stuck booking (customer contact)

1. Agents look the booking up in *Servicing*: status, payments and refunds.
2. **PAID for more than 10 minutes:** the saga refunds automatically at the ticketing deadline. If the
   booking is past that and still PAID, check the booking-service logs for its reference. The recovery job
   logs every attempt.
3. **CANCELLATION_PENDING:** waiting for the refund webhook. Check the payment in the Stripe dashboard (test
   mode), then the payment-service logs for the webhook.
4. Goodwill compensation: an admin issues a manual refund with a note (audited).

## Known platform issues

| Issue | Effect | Mitigation |
|-------|--------|------------|
| MongoDB 8.x refuses to start on Linux kernel 6.19+ ([SERVER-121912](https://jira.mongodb.org/browse/SERVER-121912), a TCMalloc rseq bug in 8.0+; no fixed release as of 2026-11-10) | The audit store would be down on modern hosts; GitHub's runners (Ubuntu 24.04 and 26) are affected | SkyBook runs **MongoDB 7.0** (compose.yaml and MongoStoresIT), which uses the older allocator and is supported until 2027. The notification stores use only basic documents and indexes. Move to 8.x once a release with the fix ships |

## Routine operations

| Task | How |
|------|-----|
| Deploy | GitHub → Actions → *deploy* → environment + image tag (defaults to the commit). `--atomic` rolls back a failed rollout |
| Roll back | `helm rollback skybook <revision> -n skybook`, or redeploy an older tag |
| Rotate the personal-data encryption key | Add a new key id in Vault (`skybook.crypto.keys.<id>`), set `active-key` to it, roll the services. Old keys stay for decryption (ADR-0009) |
| Rotate the Stripe webhook secret | Create a new endpoint secret in Stripe, update Vault `secret/payment-service`, roll payment-service, delete the old secret |
| Rotate service certificates | Automatic (cert-manager renews 10 days before expiry; Spring reloads without restart). Force: `kubectl -n skybook delete secret <service>-tls` |
| Restore a database | Point-in-time restore of the managed database to a new instance, repoint the service's `*_DB_URL`, roll the service. Outbox rows not yet published are relayed after the restore |
