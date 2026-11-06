## What and why

<!-- What does this change do, and which problem does it solve? Link the issue or phase. -->

Closes #

## Type of change

- [ ] Feature
- [ ] Bug fix
- [ ] Refactoring (no behaviour change)
- [ ] Infrastructure / CI
- [ ] Documentation

## How it was tested

<!-- Commands run, test classes added, manual checks. Paste relevant output. -->

- [ ] `./mvnw verify` passes locally (Checkstyle, unit, integration, ArchUnit, coverage gate)
- [ ] New or changed behaviour is covered by tests (domain logic ≥ 80% line coverage)
- [ ] Integration tests use Testcontainers, not a shared local database

## Design checklist

- [ ] API changes are made in the OpenAPI spec first; breaking changes go to a new version (`/api/v2`)
- [ ] Errors are returned as RFC 9457 problems with a stable `code`
- [ ] New events use `EventEnvelope`, are published through the outbox, and their consumers are idempotent
- [ ] Database changes are new Flyway migrations (never edit an applied one) and include the indexes they need
- [ ] Calls to partners have a timeout, retry/circuit breaker and a fallback
- [ ] A significant design decision has an ADR in `docs/adr/`

## Security and privacy checklist

- [ ] No secrets, tokens or real personal data in code, tests, logs or fixtures
- [ ] Endpoints declare the required role (`CUSTOMER` / `AGENT` / `ADMIN`)
- [ ] Personal data is encrypted at rest and not written to logs or error responses
- [ ] Card data never reaches our services (Stripe Elements only)

## Deployment notes

<!-- Config, migrations, feature flags, rollout order, rollback plan. Write "None" if there are none. -->
