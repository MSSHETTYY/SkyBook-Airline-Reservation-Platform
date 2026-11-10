# ADR-0022: Mobile app with Expo (React Native)

- **Status:** Accepted
- **Date:** 2026-11-10
- **Deciders:** SkyBook architecture

## Context

Most airline traffic comes from phones: checking a booking at the airport, paying on the go, cancelling
when plans change. The web UI is responsive, but a native app adds:
- secure token storage in the device keychain;
- deep links back from the login page;
- a home-screen presence.

The team already writes React and TypeScript (ADR-0017). Everything must be free and buildable without
paid accounts.

## Decision

- **Expo SDK 54** (React Native 0.81, React 19.1) with **expo-router** (file-based routes, mirroring the
  web pages).
  - We use the newest established SDK rather than the latest major, following the same version policy as
    the web UI.
  - Versions are aligned with `expo install --check`.
- **API-first again.** The `typescript-fetch` clients are generated from the same OpenAPI specs. Search uses
  **inventory API v2** (ADR-0020): a three-day window priced per cabin, which suits a small screen better
  than a single day.
- **Sign-in:**
  - Authorization code + PKCE against a dedicated public Keycloak client, `skybook-mobile`, with a custom
    scheme redirect `skybook://auth` (plus `exp://` for Expo Go during development).
  - Tokens are stored with **expo-secure-store** (iOS Keychain / Android Keystore) and refreshed before
    they expire. A failed refresh signs the user out.
- **Mobile-first booking:**
  - Instead of a seat map, seats are **assigned automatically**: consecutive free seats in one row of the
    chosen cabin, falling back to the first free seats. Seat selection stays a web feature.
  - One idempotency key per attempt, renewed after a failure.
- **Same payment model:**
  - In test mode the app uses the fake provider's simulation endpoint.
  - With Stripe, the next step is the Stripe React Native SDK's PaymentSheet. Card data would stay inside
    Stripe's SDK, so the scope remains SAQ A.
- **Accessibility:**
  - every control has an accessibility role and label;
  - touch targets are at least 48 dp;
  - errors use `accessibilityRole="alert"`;
  - status changes are announced through a live region.
- **Tests:**
  - jest-expo and React Native Testing Library;
  - expo-router's `renderRouter` drives the real screens against a fake `fetch`;
  - the auth provider is tested with the Expo auth modules mocked;
  - an 80% line coverage gate, run in CI (`mobile` job).

## Alternatives considered

| Option | Pros | Cons |
|--------|------|------|
| Native Kotlin + Swift | Best performance and platform fit | Two more codebases and languages for one small team |
| Flutter | Fast UI, one codebase | Dart: no sharing of skills, generated clients or patterns with the React web UI |
| PWA only | No app stores | No keychain storage, weaker deep links and offline behaviour on iOS |
| Bare React Native (no Expo) | Full native control | Manual native builds; Expo's prebuild and EAS cover what SkyBook needs |

## Consequences

- One language and one API generation flow across web and mobile. The screens reuse the same backend
  behaviour (saga polling, problems, idempotency).
- Not verified on a device or emulator in this repository: unit and integration tests run in Node, and
  device builds need Expo Go or EAS Build on the developer's side (see `mobile/README.md`).
- Follow-ups:
  - Stripe PaymentSheet;
  - push notifications for status changes, which would come from notification-service through Expo push;
  - Detox or Maestro end-to-end tests on an emulator.
