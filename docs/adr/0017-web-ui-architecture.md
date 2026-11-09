# ADR-0017: Web UI architecture: React SPA, generated clients, mock-first testing

- **Status:** Accepted
- **Date:** 2026-11-09
- **Deciders:** SkyBook architecture

## Context

Customers search, book, pay, change and cancel trips. Agents service any booking, and admins also
manage flights and issue goodwill refunds. The portal must:

- meet **WCAG 2.1 AA**;
- stay in step with three OpenAPI-first backends;
- never touch card data, to keep PCI scope minimal;
- be testable end to end on a laptop with 8 GB RAM and no Docker running.

Two things in the backend shape the UI:

- The backend is asynchronous. A paid booking becomes CONFIRMED only after the saga has issued
  tickets (ADR-0014).
- Every state-changing call needs an `Idempotency-Key` (ADR-0007).

## Decision

**Stack.** React 19 + TypeScript (strict), Vite, MUI 7, TanStack Query 5, React Hook Form + zod,
React Router 7, oidc-client-ts.

- We pin the newest release of each library's previous major (TypeScript 5.9, MUI 7, Router 7,
  MSW 2, Vitest 3, Vite 7) rather than the brand-new majors (TypeScript 7, MUI 9, Router 8, ...).
  Their APIs and ecosystem support (typescript-eslint, RTL, plugins) are settled.
- Exact versions are pinned in `package.json`, and Renovate/Dependabot proposes upgrades.

**API access.**
- `typescript-fetch` clients are generated from `api/*/v1/*.yaml` by the same openapi-generator
  version the services use, so a contract change breaks the UI build instead of production.
- A single module (`src/api/client.ts`) adds the bearer token and an `X-Correlation-Id`.
- Every call goes through `call()`, which converts failures to the RFC 9457 `ApiProblem`. That type
  is registered as TanStack Query's error type, so pages never handle raw `Response` objects.

**Server state.**
- TanStack Query owns all server data.
- Booking pages poll every 2 s only while the status is transient (PENDING_PAYMENT, PAID,
  CHANGE_PENDING_PAYMENT, CANCELLATION_PENDING) and stop at a terminal state. WebSockets or SSE would
  be the next step if load requires it.
- Client errors (4xx) are not retried. Mutations are never retried automatically.

**Idempotency.** `useIdempotencyKey()` returns the same key for the same payload, so a double click
or a retry can never create two bookings. A changed payload, for example a new seat after a
SEAT_UNAVAILABLE conflict, gets a new key, because the server rejects a reused key with a different
body.

**Auth.**
- Authorization code + PKCE against the public Keycloak client `skybook-web`.
- Tokens are kept in `sessionStorage`, with silent renew.
- Roles are read from `realm_access.roles` only to decide what to *show*. The gateway and the
  services enforce authorization.
- A `SessionContext` hides the mechanism. A mock provider (a role picker) is compiled in only for
  `--mode mock` builds.

**Payments.**
- With a Stripe publishable key and a real `pi_..._secret_...` client secret, Stripe's Payment
  Element (iframe) collects the card. SkyBook never sees card data (SAQ A).
- Otherwise the page offers the fake provider's signed simulation endpoint.
- The UI never decides that a payment succeeded. The webhook-driven saga does.

**Accessibility by construction.**
- Skip link and landmarks.
- One focused `h1` per route, with `document.title` updated on navigation.
- Seat map built from real buttons, each with `aria-pressed` and a full spoken label.
- State shown by icons as well as colour.
- Live regions for status changes. The hold countdown is announced once per minute, not every
  second.
- Errors associated with their fields. Colour contrast is at least 7:1 for the brand colours.
- `prefers-reduced-motion` is honoured.

**Mock-first testing.** An MSW mock API (`src/mocks`) implements the specs' wire format and the key
business rules: holds, refund tiers, the change fee, ticketing delay, and a deliberate seat-conflict
trap on seat 3A. The same mock serves three purposes:

1. Vitest/RTL tests: 69 tests, ~97% line coverage, an 80% gate.
2. `npm run dev:mock`.
3. Playwright, against the **production bundle built in mock mode**.

Playwright runs the journeys on Chromium, Firefox, WebKit and a Pixel 7 viewport.
- axe-core WCAG 2.1 A/AA scans run on Chromium, desktop and mobile. axe evaluates the DOM and ARIA
  tree, which is engine-independent for this markup.
- Under memory pressure, Firefox's content process also crashed inside axe-core. Every screen passed
  when scanned alone.
- Setting `E2E_BASE_URL` points the same specs at a real deployment.

**Delivery.** A multi-stage image:
1. openapi-generator stage;
2. Node build stage;
3. `nginx-unprivileged` serving the static files: uid 101, port 8080.

The image:
- adds CSP and other security headers;
- proxies `/api/` to the gateway, so there is a single origin and no CORS;
- writes `config.js` at start-up from `SKYBOOK_*` variables, so **one image is promoted through all
  environments**.

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Next.js (SSR) | SEO, streaming | A Node runtime in production; booking pages are behind login, so SSR adds little |
| Hand-written fetch clients | No generator step | Silent contract drift; the generated clients fail the build instead |
| Redux Toolkit / RTK Query | Mature | More ceremony; TanStack Query covers server state, and there is little client state |
| Formik + Yup | Familiar | Slower re-renders; zod schemas give inferred types |
| Tokens in memory + BFF | No token in JS-readable storage | Needs a server-side session component; planned as a hardening step (threat model) |
| Cypress | Good DX | No WebKit; Playwright covers three engines with one API |
| E2E only against the full Docker stack | Highest fidelity | ~6 GB RAM, slow, flaky on 8 GB laptops; kept as an optional target via `E2E_BASE_URL` |

## Consequences

- UI work and E2E runs need only Node. CI runs them on every PR (`web-ui` job) in a few minutes.
- The mock can diverge from the real services. Mitigations:
  - it is written against the generated types and the same specs;
  - Pact and Newman cover the real contracts (Phases 3 and 6);
  - the E2E suite can be pointed at the real stack.
- Polling costs a request every 2 s per open booking page, and only while the saga is running.
  Acceptable at demo scale; revisit with SSE if p95 load tests (Phase 7) show pressure.
- `sessionStorage` tokens are readable by injected script. The CSP (`script-src 'self'
  js.stripe.com`) is the main mitigation, and a BFF is listed in the threat model as future
  hardening.
- Library majors move fast. Pinning previous majors trades the newest features for stability.
  Upgrades are a deliberate, tested step.
