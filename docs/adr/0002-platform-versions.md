# ADR-0002: Platform baseline - Java 21, Spring Boot 4.0.x, Spring Cloud 2025.1.x

- **Status:** Accepted
- **Date:** 2026-11-06
- **Deciders:** SkyBook architecture

## Context

The brief asks for "Java 21, latest stable Spring Boot, Spring Cloud". On 2026-11-06 Maven Central
offered Spring Boot **4.1.2** as the newest release, but the newest GA Spring Cloud release train,
**2025.1.4**, declares `spring-boot.version` **4.0.9**. The Spring Cloud train that targets Boot
4.1 (2026.0) was still at RC2. Spring Cloud Gateway, Config and the Resilience4j integration are
core to this platform.

## Decision

- **Java 21 (LTS)** for all modules, enforced by `maven-enforcer-plugin`.
- **Spring Boot 4.0.9** with **Spring Cloud 2025.1.4**: the newest *GA pair that is tested together*.
- Everything else (Jackson 3.1, JUnit Jupiter 6, Testcontainers 2, Kafka clients 4.1, Flyway 11)
  is taken from the Boot BOM, never pinned separately, unless a module documents why.
- The Kafka broker image is pinned to the same version as the client in the BOM (4.1.2).
- Builds use the **Maven Wrapper** (Maven 3.9.16), so contributors and CI need only a JDK.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Boot 4.1.x + Cloud 2026.0 RC | Newest features | Pre-release Cloud train; unsupported combination; risk of breaking changes before GA |
| Boot 4.1.x + Cloud 2025.1.x | Newest Boot | Combination is not tested by the Spring Cloud team; Spring Cloud's compatibility verifier may reject it |
| Boot 3.5.x | Most third-party examples still target it | Older generation (Jackson 2, Framework 6); a portfolio project should show the current generation |

## Consequences

- Upgrade to Boot 4.1 + Cloud 2026.0 once Cloud 2026.0 is GA; one property change in the parent POM.
- Boot 4 brings **Jackson 3** (`tools.jackson.*` packages) and modular starters; examples written
  for Boot 3 do not always apply.
- `libs/common` deliberately depends on `spring-webmvc` and `spring-boot-autoconfigure` instead of a
  starter, so services stay free to choose their own embedded server and starters.
