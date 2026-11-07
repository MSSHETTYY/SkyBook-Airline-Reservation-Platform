# ADR-0011: Secrets in HashiCorp Vault via Spring Cloud Vault

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

The services need database passwords, OAuth2 client secrets, Stripe keys and the personal-data
encryption keys. Those must never be committed, baked into images, or visible in plain Kubernetes
manifests, and they must be rotatable without code changes.

## Decision

- **HashiCorp Vault** stores the secrets (KV v2 engine at `secret/<service-name>`). Each service only
  reads its own path.
- **Spring Cloud Vault** loads them at start-up through `spring.config.import: vault://` (profile
  `vault`). Vault values override the development defaults in `application.yml`, so the code
  never changes between environments.
- **Authentication:**
  - locally, a dev-mode root token (`docker compose --profile secrets`, seeded by
    `infra/vault/init-secrets.sh`);
  - on Kubernetes, the **Kubernetes auth method**: each pod logs in with its service-account JWT and
    receives a short-lived token bound to a per-service policy (Helm chart, Phase 6). No static token
    exists in the cluster.
- **`fail-fast: true`**: a service whose secrets cannot be loaded refuses to start, instead of
  starting with development defaults.
- **Development defaults** remain in `application.yml` only so that `mvn verify` and IDE runs work
  without any infrastructure. They are clearly labelled and unusable outside localhost.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Kubernetes Secrets only | Native, no extra component | Base64, not encrypted by default; rotation means redeploying; no audit trail |
| Sealed Secrets / SOPS in git | GitOps-friendly | Still static; rotation needs a commit; no dynamic credentials |
| OpenBao (open-source Vault fork) | MPL-licensed; API-compatible | The brief names HashiCorp Vault. Switching is a one-line image change if licensing matters, because Vault itself is source-available (BSL), not OSI open source. |
| Cloud secret managers | Managed | Paid cloud services are out of scope |

## Consequences

- Dev-mode Vault is in-memory. The seed script therefore uses a **fixed** development encryption key,
  so data written in an earlier run stays readable. Production Vault uses persistent storage, and keys
  are generated once and rotated (ADR-0009).
- `VaultSecretsIT` proves the key ring and the client secret really come from Vault.
- A Vault outage blocks *start-up*, not running services: values are read once at boot. Kubernetes
  rolling updates keep the old pods running while new ones fail fast.
