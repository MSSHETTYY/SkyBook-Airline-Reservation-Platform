# ADR-0020: API versioning: URI major versions with in-band deprecation

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

Flight search v1 (`GET /api/v1/flights`) returns every flight of a single day as a bare array, with one
total seat count and one from-price. Customers asked for flexible dates and cabin-specific prices, and
partners (metasearch) asked for paging. Changing the v1 response would break existing clients: the
web UI, partner integrations and the Pact contracts.

## Decision

- **Major versions in the path** (`/api/v1`, `/api/v2`). A new major only for breaking changes. Additive
  changes, such as new optional fields or new endpoints, stay in the current version, and clients must
  ignore unknown fields.
- Each major version has its **own OpenAPI document** (`api/inventory/v2/inventory-api.yaml`) and its own
  generated interfaces (`com.skybook.inventory.api.v2`). Both versions share the domain and service layer:
  `FlightOfferService` reuses the same repository query and cached seat maps.
- `GET /api/v2/flights` adds:
  - a departure date range (up to 7 days, enforced: wider ranges scan too many seat maps);
  - per-cabin offers: seats left and a from-price per cabin;
  - cabin and maximum-price filters, sorting by departure or price;
  - a paging envelope.
- **The old version announces its retirement in every response:**
  - `Deprecation: @<unix time>` (RFC 9745);
  - `Sunset: <HTTP date>` (RFC 8594), here 30 June 2027;
  - `Link: </api/v2/flights>; rel="successor-version"`.

  Clients and API gateways can detect this automatically, and usage of v1 is visible in the
  `http.server.requests` metrics by URI, which tells us when the sunset can actually happen.
- The gateway routes and the security rules list each public version explicitly. A new version is never
  exposed by accident.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Media-type versioning (`Accept: application/vnd.skybook.v2+json`) | Clean URIs | Harder to test and cache, invisible in logs and metrics by URI, awkward for partners |
| Header versioning (`API-Version: 2`) | Same | Same drawbacks; easy to forget at the gateway and in caches |
| Evolve v1 in place with optional parameters | No new version | The response shape must change (envelope, per-cabin data), which breaks v1 clients |
| Date-based versions (Stripe style) | Fine-grained | Needs per-account version pinning and transformation layers; overkill for SkyBook's size |

## Consequences

- Two contracts to maintain until the sunset. They are cheap because only the mapping layer differs.
- The web UI keeps using v1 for now and moves to v2 when it adopts flexible dates. Pact contracts for v1
  stay valid until the sunset.
- Removing v1 needs evidence: zero v1 traffic in the metrics for 30 days, or the sunset date reached with
  partners notified.
