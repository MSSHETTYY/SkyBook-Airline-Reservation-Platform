# ADR-0008: API security - Keycloak JWTs, realm roles, method-level RBAC

- **Status:** Accepted
- **Date:** 2026-11-07
- **Deciders:** SkyBook architecture

## Context

Customers manage their own bookings, agents service any booking, admins also manage flights and
issue manual refunds, and services call each other. Identity must be verified on every service,
not only at the edge, so that a compromised network segment cannot bypass authorisation.

## Decision

- **Keycloak** is the OpenID Connect provider. The SPA uses the authorization code flow with PKCE.
  Services authenticate to each other with the **client credentials** grant.
- **Every service is an OAuth2 resource server.** It validates JWT signatures against the realm's
  JWKS and the issuer, statelessly. The gateway validates tokens too (defence in depth), but services
  never trust the gateway alone.
- **Realm roles** (`realm_access.roles`): `CUSTOMER`, `AGENT`, `ADMIN`, `SERVICE`. They are mapped to
  Spring authorities `ROLE_*` by `KeycloakRealmRoleConverter` in the common library.
- **The security baseline is shared** (`SkyBookHttpSecurity`): stateless sessions, no CSRF (bearer
  tokens only, no cookies), RFC 9457 problem bodies for 401 and 403, and public actuator health and
  Prometheus endpoints.
- **Authorisation is method-level** (`@PreAuthorize`, with role expressions defined once in
  `Roles`):
  - inventory: search and seat maps are public; holds and seat releases are `SERVICE` only; flight
    management is `ADMIN`; flight listing is `AGENT`/`ADMIN`.
  - booking: create and list-mine are `CUSTOMER`; get-one is any authenticated caller, but the
    service returns 404 unless the caller owns the booking or is staff; search is staff only.
- **Object-level checks live in the application service** (`BookingQueries.get`), not in the
  controller, so every entry point enforces them. Customers probing references they do not own get
  404, not 403, so references cannot be enumerated.
- **The customer id is always the token subject**, never a request field.
- **Start-up does not depend on Keycloak:** JWKS is fetched lazily, and the client registration uses
  an explicit token URI instead of OIDC discovery.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Validate only at the gateway; services trust headers | Simpler services | One misrouted request or compromised pod bypasses all checks |
| URL-pattern rules in `SecurityFilterChain` | Central | Drift between routes and rules; object-level rules cannot be expressed there |
| Keycloak client roles per service | Fine-grained | More configuration; realm roles are enough for four roles |

## Consequences

- Tests use real RS256-signed tokens (`TestJwtIssuer`) against the real filter chain, so role
  mapping and 401/403 bodies are verified end to end.
- `@PreAuthorize` failures thrown inside controllers are mapped to 403 problems by
  `SecurityProblemHandler`, ordered ahead of the catch-all handler that would otherwise return 500.
