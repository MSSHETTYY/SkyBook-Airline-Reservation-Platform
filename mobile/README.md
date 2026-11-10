# SkyBook mobile (Expo SDK 58)

React Native app for customers: search flights (API v2, three-day window), sign in with Keycloak, book
with automatically assigned seats, pay (test mode), track the booking live and cancel with a refund quote.
Design: [ADR-0022](../docs/adr/0022-mobile-app.md).

## Run it

Prerequisites: Node 20.19+ and the JDK (for the API client generator). The backend must be running
(gateway on :8080, Keycloak on :8180; see the root README).

```bash
cd mobile
npm install
npm run generate:api      # TypeScript clients from ../api
npm test                  # Jest + React Native Testing Library, coverage gate 80%
npx expo start            # then press "a" for an Android emulator, or scan the QR code with Expo Go
```

Addresses come from `app.json` → `expo.extra`:

| Setting | Default | Change it when |
|---------|---------|----------------|
| `apiBaseUrl` | `http://10.0.2.2:8080` | 10.0.2.2 is the host machine as seen from the Android emulator. On a physical phone use your computer's LAN IP, e.g. `http://192.168.1.20:8080` |
| `oidcIssuer` | `http://10.0.2.2:8180/realms/skybook` | Same host rule as above |
| `oidcClientId` | `skybook-mobile` | Never, unless you rename the Keycloak client |

Sign-in uses the `skybook-mobile` Keycloak client (in `infra/keycloak/skybook-realm.json`). Its redirect
URIs are `skybook://*` for development builds and `exp://*` for Expo Go. Demo user:
`customer@skybook.test`.

Note: Keycloak puts its own hostname in the token issuer (`http://localhost:8180/...` in the compose
setup). When the phone reaches Keycloak through another address, start Keycloak with `KC_HOSTNAME` set to
an address that both the phone and the services can reach (for example your LAN IP), and use the same
value for `oidcIssuer`.
