# SkyBook - Airline Reservation & Servicing Platform

SkyBook is an airline booking platform built as event-driven microservices. Customers search
flights, hold seats (holds expire after 15 minutes), pay with Stripe (test mode), receive a ticket,
and can change or cancel the booking with a refund. Agents and admins manage flights and inventory
and service bookings.

The project shows production engineering concerns rather than CRUD: no overbooking under
concurrent load, sagas with compensations, a transactional outbox, idempotent APIs and consumers,
resilience around unreliable partners, OAuth2/OIDC security with mTLS, and full observability.
Everything runs locally on free, open-source tools.

> **Status:** built in phases. See [Roadmap](#roadmap).

## Architecture

```mermaid
flowchart LR
    user([Customer / Agent / Admin])
    spa[web-ui<br/>React + TS]
    kc[Keycloak<br/>OIDC + PKCE]
    gw[api-gateway<br/>JWT, rate limiting]

    subgraph services[Services]
        inv[inventory-service]
        book[booking-service<br/>saga orchestrator]
        pay[payment-service]
        notif[notification-service]
    end

    ora[(Oracle Free<br/>flights, seats)]
    redis[(Redis<br/>availability read model)]
    mdb1[(MariaDB<br/>booking)]
    mdb2[(MariaDB<br/>payment)]
    mongo[(MongoDB<br/>audit trail)]
    kafka{{Kafka KRaft<br/>domain events}}
    solace{{Solace PubSub+<br/>notifications}}
    stripe[[Stripe test mode]]
    partners[[mock-partners<br/>GDS, CRM, loyalty]]
    mail[[Mailpit]]

    user --> spa
    spa -- login --> kc
    spa -- /api/v1 --> gw
    gw --> inv & book & pay
    inv --- ora & redis
    book --- mdb1
    pay --- mdb2
    book -- REST, mTLS --> inv & pay
    book -- Resilience4j --> partners
    pay --> stripe
    inv & book & pay -- outbox --> kafka
    kafka --> inv & book
    kafka -- bridge --> solace --> notif
    notif --> mongo & mail
```

| Concern            | Approach                                                                              |
|--------------------|---------------------------------------------------------------------------------------|
| Consistency        | Database per service, an orchestrated saga in booking-service, a transactional outbox |
| Seat allocation    | Decided only by inventory-service against Oracle, using optimistic locking and expiring holds |
| Read scaling       | CQRS: availability search is served from a Redis read model kept up to date by events |
| Errors             | RFC 9457 `application/problem+json` with a stable `code` and a `correlationId`        |
| Traceability       | `X-Correlation-Id` on every request, log line (MDC) and event envelope                |

Decisions are recorded in [docs/adr](docs/adr):
[0001 architecture style](docs/adr/0001-architecture-style.md) ·
[0002 platform versions](docs/adr/0002-platform-versions.md) ·
[0003 local footprint](docs/adr/0003-local-runtime-footprint.md) ·
[0004 CI platform](docs/adr/0004-ci-platform.md)

## Repository layout

```
skybook/
├─ pom.xml               Parent POM: Boot 4.0 and Cloud 2025.1 BOMs, Checkstyle, JaCoCo gate, enforcer
├─ libs/common/          Shared technical building blocks (no business logic)
│   ├─ error/            ErrorCode, SkyBookException, GlobalProblemHandler (RFC 9457)
│   ├─ web/              CorrelationIdFilter, CorrelationId
│   ├─ event/            EventEnvelope<T>, EventTypes (framework-free)
│   └─ autoconfigure/    Spring Boot auto-configuration for servlet services
├─ mock-partners/        WireMock stubs for GDS, CRM and loyalty
├─ infra/                Container init scripts
├─ config/checkstyle/    Code style rules
├─ docs/adr/             Architecture decision records
├─ compose.yaml          Local infrastructure, with profiles
└─ .github/              CI workflow, pull request template
```

## Getting started

**Prerequisites:** JDK 21 and Docker Desktop. Maven is not needed because the repository includes
the Maven wrapper.

On a machine with 8 GB of RAM, first copy [docs/dev/wslconfig.example](docs/dev/wslconfig.example)
to `%UserProfile%\.wslconfig` to cap Docker at 4 GB.

```bash
./mvnw verify                                 # Checkstyle, tests, ArchUnit, coverage gate (Windows: mvnw.cmd verify)
cp .env.example .env                          # optional: override development credentials
docker compose --profile core up -d           # Oracle, MariaDB, Redis
docker compose ps                             # wait until every container is "healthy"
docker compose --profile core down            # stop the containers (add -v to delete their data)
```

### Compose profiles

Run one or two profiles at a time. [ADR-0003](docs/adr/0003-local-runtime-footprint.md) explains why.

| Profile        | Containers             | Memory limit | Needed for       |
|----------------|------------------------|--------------|------------------|
| `core`         | Oracle, MariaDB, Redis | ~2.5 GB      | Phases 1-3       |
| `messaging`    | Kafka, Solace          | ~2.5 GB      | Phases 3-4       |
| `auth`         | Keycloak               | 768 MB       | Phase 2 onward   |
| `notification` | MongoDB, Mailpit       | ~0.6 GB      | Phase 4          |
| `partners`     | WireMock               | 256 MB       | Phase 3 onward   |

### Ports

| Port  | Component                        | Port  | Component                  |
|-------|----------------------------------|-------|----------------------------|
| 1521  | Oracle (service `FREEPDB1`)      | 8025  | Mailpit web UI             |
| 3306  | MariaDB (`booking`, `payment`)   | 1025  | Mailpit SMTP               |
| 6379  | Redis                            | 8095  | WireMock (mock partners)   |
| 27017 | MongoDB                          | 8180  | Keycloak                   |
| 9092  | Kafka (from the host)            | 8190  | Solace PubSub+ Manager     |
| 55555 | Solace SMF (JMS)                 | 8080  | api-gateway (Phase 2)      |

The application services will use ports 8081-8085.

## Roadmap

| Phase | Scope                                                                                      | Status  |
|-------|--------------------------------------------------------------------------------------------|---------|
| 0     | Monorepo, parent POM, common library, compose infrastructure, CI skeleton, ADRs            | Done    |
| 1     | OpenAPI specs, inventory-service, booking-service (hexagonal), concurrency proof           | Planned |
| 2     | Keycloak, api-gateway, RBAC, mTLS, encryption of personal data at rest, Vault              | Planned |
| 3     | payment-service (Stripe), saga with compensations, outbox, DLTs, Resilience4j, Pact        | Planned |
| 4     | notification-service (Solace JMS, Mailpit, MongoDB audit trail)                            | Planned |
| 5     | web-ui (React + TS, accessible), Vitest/RTL, Playwright + axe                              | Planned |
| 6     | Dockerfiles, Helm on kind, full CI/CD (SonarQube, Trivy, Newman, GHCR, deploy)             | Planned |
| 7     | OpenTelemetry, Prometheus/Grafana SLOs, Loki, k6 load test, chaos tests, final docs        | Planned |
| 8     | Optional: Spring AI booking assistant (Ollama), Expo mobile app                            | Planned |

## Contributing

Work on short-lived branches off `main` and merge through a pull request using the
[template](.github/pull_request_template.md). CI must pass before a merge.
