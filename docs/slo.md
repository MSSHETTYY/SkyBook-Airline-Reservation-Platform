# Service level objectives

SLOs describe the reliability customers should experience. They drive alerting (page only when the
error budget is burning fast) and prioritisation (an exhausted budget freezes feature work).

## SLIs and objectives

| SLO | SLI (good events / valid events) | Objective | Window |
|-----|----------------------------------|-----------|--------|
| Availability | Gateway API responses that are not 5xx / all gateway API responses (actuator excluded) | 99.5% | 30 days rolling |
| Search latency | Flight searches answered within 300 ms / all flight searches | 95% | 30 days rolling |

Error budget for availability: 0.5% of requests, about 3.6 hours of total outage per 30 days.

Not yet an SLO: **event freshness** (outbox events relayed within 60 s). It needs an outbox-lag gauge in
`libs/messaging`, which is a follow-up. Until then a Kafka outage shows up as stuck sagas
([runbook](runbook.md#kafka-outage)).

The SLIs are computed from `http_server_requests_seconds` histograms (enabled in every service's
`application.yml`). They are recorded as `skybook:*` series by
[skybook-slo-rules.yml](../deploy/helm/skybook/files/skybook-slo-rules.yml), which is the single source
for the local Prometheus and the Kubernetes PrometheusRule.

## Alerting: multi-window, multi-burn-rate

| Alert | Condition | Meaning | Action |
|-------|-----------|---------|--------|
| `SkyBookErrorBudgetFastBurn` (page) | error ratio > 14.4 × 0.5% over 1 h **and** 5 min | 2% of the monthly budget gone in an hour | Respond now: [runbook](runbook.md#high-error-rate) |
| `SkyBookErrorBudgetSlowBurn` (ticket) | error ratio > 6 × 0.5% over 6 h **and** 30 min | 5% of the budget gone in 6 hours | Fix within the working day |
| `SkyBookFlightSearchSlow` (ticket) | p95 > 300 ms for 10 min | Latency objective at risk | [runbook](runbook.md#slow-flight-search) |

The short window stops an alert from firing long after the problem is fixed, and the long window stops
short blips from paging anyone.

## Error budget policy

- **Budget remaining > 50%:** normal delivery; chaos experiments and risky migrations are allowed.
- **25-50%:** releases need a rollback plan reviewed by a second engineer; no chaos experiments in production.
- **< 25%:** only fixes and reliability work ship until the budget recovers above 25%.
- **Exhausted:** feature freeze; a post-mortem is mandatory for every incident that consumed more than 20%
  of the budget ([template](postmortems/README.md)).

## Dashboard

Grafana → folder *SkyBook* → **SkyBook - service overview and SLOs** shows:
- availability and error budget left;
- search p95 against the objective;
- per-service traffic, errors and latency;
- circuit breakers and heap;
- the latest error logs, linked to their traces.
