# ADR-0010: Mutual TLS between services

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

JWTs authenticate *callers* (users or services), but without transport security, traffic inside the
cluster can be read or spoofed by anything that reaches the pod network. Zero-trust networking
requires that every hop is encrypted and that both ends prove their identity.

## Decision

- Every service listens with **TLS and `client-auth: need`** (profile `mtls`). Callers must present a
  certificate signed by the SkyBook CA, and services verify each other's host names against the
  certificate SANs.
- **Spring Boot SSL bundles with PEM files** (`spring.ssl.bundle.pem.skybook-tls`). The same bundle
  serves as the server identity and as the client certificate for outgoing calls: booking's
  `RestClient` through the JDK `HttpClient`, and the gateway's Netty client through
  `httpclient.ssl.ssl-bundle`.
- **`reload-on-update: true`** picks up renewed certificates without a restart. That matters because
  cert-manager renews them automatically.
- **Certificates:** on Kubernetes, **cert-manager** issues them from an internal CA `ClusterIssuer`
  (the Helm chart, Phase 6) into secrets mounted at `/etc/skybook/tls`. Locally,
  `infra/certs/generate-dev-certs.sh` uses **mkcert** when installed, otherwise OpenSSL.
- **Actuator runs on a separate plain-HTTP management port** (9081/9082/...), which the kubelet
  (probes) and Prometheus can reach without client certificates. It must never be exposed outside
  the cluster. NetworkPolicies restrict it.
- Browser traffic is TLS-terminated at the ingress. The gateway then calls the services over mTLS.
- **mTLS and JWT are complementary.** mTLS answers "is this a SkyBook workload?", and the JWT
  answers "which user or service, with which roles?".

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Service mesh (Istio, Linkerd) sidecars | Transparent mTLS, rotation and policy | Another platform to run; sidecars add memory, and the reference machine has only 8 GB |
| TLS without client authentication | Simpler | Encrypts but does not authenticate the caller |
| JKS/PKCS12 key stores | Familiar | cert-manager and mkcert produce PEM natively; converting adds steps and passwords |

## Consequences

- `MutualTlsTest` (inventory) proves that the API port rejects callers without a certificate and
  callers whose certificate comes from another CA, while health probes work on the management port.
  `InventoryMutualTlsTest` (booking) proves the client presents its certificate.
- Throwaway test certificates (CA private key deleted) are committed under `libs/common` test
  resources. Real certificates are never committed (`infra/certs/out/` is git-ignored).
- Local development runs without mTLS by default (the profile is off) to keep the inner loop simple.
  It is enabled in the Kubernetes deployment.
