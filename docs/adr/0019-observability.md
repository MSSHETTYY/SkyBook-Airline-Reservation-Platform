# ADR-0019: Observability: OpenTelemetry traces, Prometheus metrics with SLOs, structured logs in Loki

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

One booking touches the gateway, three services, Kafka, Solace and three partners. Correlation ids
(Phase 1) already tie logs and events together, but we still could not:
- see where time goes inside a request;
- alert on what customers experience;
- search logs across services.

The tools must be free, run locally in under 1 GB, and map one to one onto managed equivalents in the cloud.

## Decision

- **Traces:** `spring-boot-starter-opentelemetry`, which bridges Micrometer Observation to the OpenTelemetry
  SDK, in every service. Spans go over OTLP/HTTP to **Jaeger** locally (`jaegertracing/jaeger` v2, which is
  an OpenTelemetry Collector distribution) or to any OTLP collector in Kubernetes.
  - W3C `traceparent` propagation.
  - The export endpoint is set only by environment (`MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT`).
    Tests and plain local runs export nothing.
  - Sampling is 100% locally and 10% by default in Kubernetes.
- **Metrics:**
  - `micrometer-registry-prometheus` exposes `/actuator/prometheus` on the management port.
  - Every metric is tagged `application=<service>`.
  - `http.server.requests` publishes **histograms and SLO buckets (100 ms, 300 ms, 1 s)**, so latency
    percentiles and objectives can be computed across replicas.
  - Prometheus scrapes them: static targets in Compose, ServiceMonitors from the Helm chart in Kubernetes.
- **SLOs as code:** recording rules and **multi-window, multi-burn-rate alerts** (Google SRE workbook) live in
  one file, `deploy/helm/skybook/files/skybook-slo-rules.yml`. Compose mounts it into Prometheus, and the chart
  packages it as a PrometheusRule, so local and production alerting cannot drift. Every alert links to a
  runbook section.
- **Logs:**
  - Spring Boot structured logging in **ECS JSON** (`LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs`, set by Compose
    and Helm). The trace id and correlation id are in every line.
  - **Grafana Alloy** ships container stdout to **Loki**. The level becomes a label, and the trace id becomes
    structured metadata (high cardinality, so not a label).
- **Grafana** is provisioned from Git: data sources with log→trace and trace→log links, and the *SkyBook -
  service overview and SLOs* dashboard.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| OpenTelemetry Java agent | Zero code, broad auto-instrumentation | Extra JVM agent per image, slower start-up, two instrumentation models next to Micrometer |
| Zipkin / Brave | Mature, simple | OTLP is the vendor-neutral standard; Boot 4 has first-class OTel support |
| Grafana Tempo instead of Jaeger | Same stack as Loki/Grafana | Jaeger is what the brief asked for and has the better standalone UI; both accept OTLP |
| Promtail | Familiar | Deprecated in favour of Alloy (2025) |
| ELK / OpenSearch | Powerful search | 2-4 GB RAM; Loki indexes labels only and fits the 8 GB budget |
| `grafana/otel-lgtm` all-in-one | One container | Hides the components we want to explain and configure individually |

## Consequences

- Every request can be followed from a log line to its trace and back. Dashboards and alerts are
  reviewable diffs.
- Histograms multiply the series count of `http.server.requests` by about 15 buckets. That is acceptable
  for 6 services and a few dozen routes; revisit if routes are templated badly (high-cardinality URIs).
- **Asynchronous hops are not one trace yet.** The outbox relay publishes later, outside the request's
  context. Kafka and JMS hops are therefore linked by the `correlationId` in the event envelope, not by
  span parentage. Storing `traceparent` in `outbox_event` and restoring it in the relay and the listeners
  is the follow-up that would make sagas one trace.
- Not yet measured: outbox lag (event freshness SLO, `docs/slo.md`) and saga business metrics (bookings
  per state). Both are listed as follow-ups.
