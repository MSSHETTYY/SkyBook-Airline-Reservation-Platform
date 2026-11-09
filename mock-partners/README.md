# mock-partners

WireMock stands in for the external partner systems, so every failure mode (latency, timeouts, 5xx) can
be reproduced on demand. Start it with `docker compose --profile partners up -d`
(admin API: http://localhost:8095/__admin).

| Partner   | Endpoint                          | Behaviour |
|-----------|-----------------------------------|-----------|
| GDS       | `POST /gds/v1/tickets`            | One 13-digit ticket per passenger (requires `Idempotency-Key`) |
| GDS       | `POST /gds/v1/tickets/void`       | 204 |
| CRM       | `GET /crm/v1/customers/{id}`      | `GOLD` if the id ends with `-gold`, otherwise `STANDARD` |
| Loyalty   | `POST /loyalty/v1/accruals`       | 202 after 150 ms |

## Fault injection

| Trigger | Effect | Demonstrates |
|---------|--------|--------------|
| Book flight number **SB999** | GDS always answers 503 | retries, circuit breaker, saga compensation (refund + seats released) |
| Book flight number **SB998** | GDS answers after 6 s | read timeout (4 s) and retry |
| Any partner, at runtime | `POST /__admin/settings {"fixedDelay": 3000}` adds global latency; `POST /__admin/reset` restores | bulkheads and timeouts under slowness |
| Stop the container | Connection refused | CRM falls back to STANDARD tier; loyalty accrual is skipped and logged |

Mappings live in `wiremock/mappings/` and are mounted read-only into the container.
