# ADR-0003: Local runtime footprint on a 7.8 GB development machine

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** SkyBook architecture

## Context

The reference development machine has **7.8 GB of RAM**. The full platform (Oracle Free, MariaDB,
Redis, MongoDB, Kafka, Solace, Keycloak, Mailpit, WireMock, Vault, the observability stack and six
JVM services) needs well over 10 GB when everything runs at once. Windows, the IDE and a browser
need about 3 GB on their own.

## Decision

1. **Cap the Docker VM at 4 GB** (`docs/dev/wslconfig.example`) so the host stays responsive.
2. **Compose profiles grouped by the phase or flow they serve**, run one or two at a time:

   | Profile        | Containers               | Memory limit |
   |----------------|--------------------------|--------------|
   | `core`         | Oracle, MariaDB, Redis   | ~2.5 GB      |
   | `messaging`    | Kafka, Solace            | ~2.5 GB      |
   | `auth`         | Keycloak                 | 768 MB       |
   | `notification` | MongoDB, Mailpit         | ~0.6 GB      |
   | `partners`     | WireMock                 | 256 MB       |

3. **Every container has a `mem_limit`**, and JVM-based containers have explicit heap caps.
4. **One MariaDB server, with one schema and one user for each service** (`booking`, `payment`).
   Each user has privileges only on its own schema. That keeps database-per-service ownership,
   because no service can read another's tables, without running a second server.
5. **Kafka runs without a volume** locally. Topics are recreated by the services that own them.
6. **Integration tests use Testcontainers with only the containers each test class needs**, so a
   test run never starts the whole stack.
7. **End-to-end demos of the full stack target kind with resource requests (Phase 6) or a larger
   machine.** They are documented, not expected to run all at once on the reference laptop.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Replace Oracle with MariaDB/Postgres | Saves ~1.5 GB | Loses the multi-database requirement (Oracle source of truth for inventory) |
| Drop Solace, Kafka only | Saves ~1.5 GB | Loses the JMS/Solace integration requirement (see the later Solace ADR) |
| Two MariaDB servers | Physical isolation | Another ~400 MB for no extra behaviour locally; production uses separate instances anyway |
| Run everything, rely on swap | No compromises in design | Thrashing makes the machine and the tests unusable |

## Consequences

- Developers must pick profiles deliberately. The README lists which profiles each phase needs.
- Sharing the MariaDB server is a **local-only** compromise. Helm values (Phase 6) give each
  service its own database instance, and nothing in the code assumes the two schemas share a server.
- Memory limits can surface out-of-memory kills during heavy tests. Container health checks and
  the `docker stats` guidance in the runbook make them visible.
