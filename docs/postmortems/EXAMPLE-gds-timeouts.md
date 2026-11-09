# EXAMPLE - Paid bookings refunded during a GDS slowdown

> **Fictional incident.** This post-mortem shows the expected format and depth. The scenario is realistic for
> SkyBook's design, and every mechanism and configuration value it cites exists in this repository. It has
> not happened in a real deployment.

- **Date:** 2026-10-14   **Duration:** 00:47   **Severity:** SEV2
- **Authors:** on-call engineer, booking team   **Status:** Reviewed
- **Error budget consumed:** 9% of the monthly availability budget; ticketing objective missed

## Summary

For 47 minutes the GDS answered ticket requests in 6-9 seconds instead of under 1 second. booking-service's
GDS client times out after 4 seconds, so ticketing failed and retried until the 10-minute ticketing
deadline. When the deadline passed, the saga refunded 38 paid bookings and marked them FAILED, which it is
designed to do. Customers were refunded in full but had to book again, and some lost their seat.

## Impact

- 38 bookings refunded and failed (2.9% of bookings in the window); 11 customers could not rebook the
  same flight because their seats had been resold.
- 61 support contacts; 11 goodwill refunds of 50 USD issued through servicing.
- Gateway availability stayed at 99.9%: the API was healthy, and the failure was asynchronous.

## Timeline (UTC)

| Time | Event |
|------|-------|
| 09:02 | GDS latency rises (seen later in the trace data) |
| 09:06 | `gds` circuit breaker opens: the failure rate of the last 20 calls exceeds 50% |
| 09:07 | `SkyBookCircuitBreakerOpen` ticket alert fires. No page: it is a ticket-level alert |
| 09:16 | First bookings pass the 10-minute ticketing deadline and are refunded |
| 09:31 | Support escalates customer complaints; on-call starts investigating |
| 09:38 | The GDS provider confirms a degraded data centre and fails over |
| 09:49 | GDS latency normal; breaker closes after a successful half-open probe; recovery job tickets the remaining PAID bookings |

## Root cause and contributing factors

1. **Trigger:** the GDS provider's partial data-centre outage.
2. **Why did slow responses become failures?** The 4-second read timeout was tuned for the GDS's normal p99
   (under 1 s). During the incident every call exceeded it.
3. **Why were bookings refunded instead of waiting?** The ticketing deadline of 10 minutes (`TICKETING_DEADLINE`
   in `BookingSagaService`) is a fixed value. It was chosen to avoid holding customers' money for long.
4. **Why did it take 24 minutes to react?** The breaker alert is a ticket, not a page. No alert covers
   "bookings stuck in PAID", which is the signal customers actually feel.

## Detection

The breaker alert fired within a minute, which was good, but nobody acted on it. The first human signal was
support, 25 minutes in. An alert on the share of bookings in PAID for more than 5 minutes would have paged
at about 09:12.

## Response and recovery

What worked:
- The saga compensated correctly: no customer was charged without a ticket.
- Breakers protected booking-service's threads, and other flows were unaffected.
- The trace links from Loki to Jaeger showed the GDS latency immediately.

What slowed us down: the runbook said what an open `gds` breaker means, but not when to escalate to the
provider.

## Action items

| Action | Type | Owner | Due |
|--------|------|-------|-----|
| Business metric `booking_saga_state` and alert "PAID older than 5 min > 1%" (page) | detect | booking team | 2026-10-28 |
| Make the ticketing deadline configurable; extend to 30 min while the GDS breaker is open | mitigate | booking team | 2026-11-11 |
| Runbook: provider escalation contacts and criteria in *Circuit breaker open* | process | on-call | 2026-10-21 |
| Ask the provider for a status webhook; show "ticketing delayed" in the UI | mitigate | product | 2026-12-01 |

## Lessons learned

- Compensation working as designed can still be a bad customer outcome. A deadline is a business decision
  and should adapt to known partner incidents.
- Alert on what customers feel (stuck bookings), not only on mechanisms (breakers).
- We got lucky that the failover happened within the hour.
