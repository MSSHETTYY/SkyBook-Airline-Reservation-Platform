# Non-functional requirements

Each requirement has a measurable target and names the place where it is verified. Targets apply in a
production-sized environment; the local Compose stack is sized for an 8 GB laptop (ADR-0003) and is used
to prove behaviour, not capacity.

| ID | Quality | Requirement | Target | Verified by |
|----|---------|-------------|--------|-------------|
| NFR-01 | Performance | Flight search latency | p95 < 300 ms at 500 concurrent users | k6 `tests/load/search-and-book.js` thresholds; SLO alert `SkyBookFlightSearchSlow` |
| NFR-02 | Performance | Seat map latency | p95 < 300 ms | k6 threshold `seatmap`; Redis read model (ADR-0006) |
| NFR-03 | Performance | Booking creation latency | p95 < 800 ms (two synchronous partner calls) | k6 threshold `create_booking` |
| NFR-04 | Scalability | Peak load | 500 concurrent users, about 20% of them booking | k6 `peak` profile; HPA on every stateless service |
| NFR-05 | Availability | API availability | 99.5% of gateway requests succeed per 30 days | `docs/slo.md`; burn-rate alerts in `skybook-slo-rules.yml` |
| NFR-06 | Consistency | No overbooking | 0 double-allocated seats under concurrency | `NoOverbookingTest` (H2) and `NoOverbookingOracleIT` (Oracle, CI) |
| NFR-07 | Consistency | No lost events | Every committed state change publishes its event, at least once | Transactional outbox; `OutboxRelayChaosIT` (Kafka paused mid-stream) |
| NFR-08 | Consistency | Idempotent writes | Retried POSTs never create duplicates | `Idempotency-Key` contract tests; Newman "retry returns the same booking" |
| NFR-09 | Resilience | Partner failures degrade gracefully | CRM down → STANDARD tier; GDS down → refund + FAILED, no stuck money | `BookingSagaServiceTest`, `PartnerAdaptersTest` (WireMock outage/slow mappings) |
| NFR-10 | Resilience | Recovery after a crash | Sagas resume within 30 s of a restart | `SagaRecoveryJob` (stall threshold 30 s), `BookingSagaServiceTest` |
| NFR-11 | Recoverability | RPO / RTO | RPO ≤ 5 min (database PITR), RTO ≤ 30 min | Runbook restore procedure; managed-database backups |
| NFR-12 | Security | Authentication | Every non-public endpoint requires a valid JWT; services authenticate each other with mTLS | `SecurityConfig` tests per service, `MutualTlsTest`, Newman 401/403 checks |
| NFR-13 | Security | Personal data at rest | Names, dates of birth, e-mails encrypted (AES-256-GCM, rotating keys) | `EncryptedStringConverter` tests (ADR-0009) |
| NFR-14 | Security | Card data | Never stored, processed or transmitted by SkyBook (PCI DSS SAQ A) | `docs/pci-dss.md`; Stripe Elements iframe; CSP |
| NFR-15 | Security | Supply chain | No fixable CRITICAL/HIGH vulnerabilities in dependencies or images | Trivy gate in CI; Dependabot |
| NFR-16 | Accessibility | Web UI | WCAG 2.1 AA, operable by keyboard only | axe scans in Playwright; keyboard journey in `booking.spec.ts` |
| NFR-17 | Compatibility | Browsers | Latest Chromium, Firefox, WebKit; phone viewport | Playwright projects |
| NFR-18 | Observability | Diagnosability | Every request traceable end to end (trace id + correlation id in logs) | OpenTelemetry traces in Jaeger, ECS logs in Loki, Grafana links (ADR-0019) |
| NFR-19 | Maintainability | Quality gates | Line coverage ≥ 80% (Java and UI), Checkstyle clean, architecture rules enforced | JaCoCo, Vitest thresholds, Checkstyle, ArchUnit in CI |
| NFR-20 | Operability | Zero-downtime deploys | Rolling updates with readiness gates; automatic rollback on failed rollout | `maxUnavailable: 0`, probes, `helm upgrade --atomic` (ADR-0018) |
| NFR-21 | Evolvability | API changes | Breaking changes ship as a new major version; the old one announces `Deprecation`/`Sunset` | `/api/v2/flights` (ADR-0020), `FlightOffersApiTest` |
| NFR-22 | Efficiency | Footprint | Each JVM service ≤ 512 MiB; whole dev stack fits an 8 GB laptop one profile at a time | Compose `mem_limit`, Helm resource limits |

## Capacity assumptions

- 500 concurrent users, 1-3 s think time between steps → about 150-250 requests/s at the gateway.
- One in five sessions books; a booking costs one inventory hold, one payment intent and, after payment,
  one hold confirmation and one GDS call.
- Seat maps are read ten times more often than they change, which is why they are served from Redis and
  evicted on every committed change.
