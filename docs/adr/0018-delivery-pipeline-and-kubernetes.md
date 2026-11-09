# ADR-0018: Container images, Helm on Kubernetes, and the CI/CD pipeline

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

Seven deployables (six Spring Boot services and the web UI) must ship as hardened images, deploy
reproducibly to Kubernetes, and pass the same quality and security gates on every change.

Constraints:
- The reference laptop has 8 GB RAM. A full Kubernetes copy of the infrastructure would not fit: Oracle,
  Kafka, Solace and Keycloak together need about 6 GB.
- Everything must be free. GitHub Actions and GHCR were chosen in ADR-0004.
- Secrets must not live in Git, and service-to-service traffic must be authenticated (ADR-0010, ADR-0011).

## Decision

**Images**

- **One parameterised Dockerfile for all Java services** (`services/Dockerfile`, `--build-arg SERVICE=`).
  - A Maven stage builds only the module and its dependencies (`-pl -am`), with a BuildKit cache for
    `~/.m2`.
  - The jar is split into Spring Boot layers (dependencies, loader, snapshots, application), so a
    code change ships one small layer.
  - The runtime is `eclipse-temurin:21-jre`, running as uid 10001 with no shell login.
  - The heap is container-aware (`MaxRAMPercentage=70`), and the JVM exits on OOM so Kubernetes
    restarts a clean JVM.
  - Every image works with a read-only root filesystem: only `/tmp` is writable.
- **web-ui**: openapi-generator stage → Node build → `nginx-unprivileged`, as described in ADR-0017.
- Tags are `sha-<commit>`, plus `latest` on main. Provenance attestations and an SBOM are pushed with
  each image.

**Helm: one chart, many services** (`deploy/helm/skybook`)

- Every Deployment is rendered from a `services:` map merged over `serviceDefaults`. Adding a service
  is a values change, not a new template.
  - The merge is hand-written because sprig's `mergeOverwrite` ignores `false` overrides.
- Each service gets:
  - a dedicated ServiceAccount, which becomes its Vault role;
  - startup, liveness and readiness probes on the actuator health groups, served on a separate plain
    management port so the kubelet and Prometheus need no client certificate;
  - a restricted security context: non-root, read-only root, all capabilities dropped,
    RuntimeDefault seccomp;
  - a `preStop` sleep, so traffic drains before graceful shutdown;
  - a CPU HPA (scale up fast, scale down slowly) and a PodDisruptionBudget;
  - soft anti-affinity;
  - a default-deny inbound NetworkPolicy that lists the services allowed to call it.
- `mtls.enabled` creates a namespace CA through cert-manager (self-signed root → CA issuer) and one
  ECDSA certificate per service, with server and client auth.
  - Certificates are mounted at `/etc/skybook/tls`, where the `mtls` Spring profile expects them.
    Rotation needs no restart (`reload-on-update`).
  - Internal URLs switch to `https://` automatically.
- `vault.enabled` adds the `vault` profile with Kubernetes auth.
  `deploy/vault/configure-kubernetes-auth.sh` creates one role and policy per service.
- One origin: the Ingress routes `/api` to the gateway and `/` to the web UI.

**Local Kubernetes = kind + Compose infrastructure** (`deploy/kind/up.sh`)

- The applications run in kind; the infrastructure stays in Docker Compose.
- Pods reach the infrastructure through `host.docker.internal`. Kafka has an extra `DOCKERHOST`
  listener for this.
- Keycloak runs with a fixed hostname, so browser tokens and service tokens carry the same issuer
  while pods fetch keys over the Docker host.

**CI (`ci.yml`) on every PR and push to main**

1. Maven `verify`: unit, Testcontainers, Pact, ArchUnit, Checkstyle and JaCoCo gates. SonarQube
   analysis with the quality gate runs when `SONAR_TOKEN` is set.
2. Web UI: lint, types, Vitest coverage gate, build, and Playwright on three engines with axe.
3. Trivy filesystem, secret and IaC scan. SARIF goes to the Security tab, and the build fails on
   fixable CRITICAL/HIGH findings.
4. `helm lint --strict`, then the rendered manifests are validated with kubeconform (including CRDs)
   for the default, kind and mTLS+Vault profiles. `docker compose config` runs for every profile.
5. Images: built on PRs (which proves the Dockerfiles work), pushed to GHCR on main, and scanned by
   Trivy.
6. On main, the gateway and web UI are deployed to an **ephemeral kind cluster with cert-manager and
   mTLS**, followed by `helm test`. This proves on a real API server that:
   - the chart installs;
   - certificates are issued;
   - pods pass their probes under the restricted security context;
   - NetworkPolicies allow the intended paths.

**Other pipelines**
- `api-tests.yml` runs nightly, on demand and on tags. It starts the whole stack in Compose and runs
  the Newman collection (`tests/api`) through the gateway: auth, idempotent booking, payment, ticketing,
  cancellation and the 401/403/400 error contract.
- `deploy.yml` is manual. It promotes an already-scanned image tag to a GitHub environment, which can
  require reviewers. It uses `helm upgrade --atomic`, so a failed rollout rolls back automatically,
  then runs `helm test`.
- Dependabot groups weekly updates for Maven, npm, Docker and Actions.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Buildpacks / Jib | No Dockerfile | Less explicit control over user, layers and JVM flags; harder to explain and review |
| One chart per service | Independent versioning | 7× duplicated templates; the services are released together today |
| Kustomize | No templating language | Repetition across 7 services; per-environment patches grow quickly |
| Full infrastructure in kind | One runtime | Does not fit in 8 GB; the infrastructure is managed services in real environments anyway |
| Istio/Linkerd for mTLS | Transparent mTLS, retries, telemetry | Heavy for kind on 8 GB; app-level mTLS with cert-manager is explicit and portable (ADR-0010) |
| ArgoCD (GitOps) | Drift detection, pull-based | Another controller to run; a natural next step once a long-lived cluster exists |
| OWASP Dependency-Check | NVD-based CVE report | Slow, needs an NVD API key; Trivy covers dependency CVEs for Maven and npm, and Dependabot raises fixes |

## Consequences

- A new service needs a `services:` entry and a matrix line in `ci.yml`. Its security posture
  (probes, security context, NetworkPolicy, certificate, Vault role) comes for free.
- kind on a laptop exercises the same chart and the same mTLS path as staging. Only the
  infrastructure endpoints differ (values files).
- **Ingress controller:** the kind setup uses ingress-nginx, which the Kubernetes project retired in
  March 2026. The chart only relies on the standard Ingress API (the class is configurable), so
  production should use a maintained controller (Traefik, HAProxy, cloud LBs) or move to the Gateway
  API.
- What is verified, and where:
  - Image builds, cluster installation and the API tests run only in GitHub Actions, because the
    development laptop has no Docker or Helm installed.
  - The first green pipeline run is therefore the real proof that these work.
- Follow-ups for Phase 7:
  - ServiceMonitors and SLO alerts (the management ports are already isolated for Prometheus);
  - cosign signing of images;
  - ArgoCD once a long-lived cluster exists.
