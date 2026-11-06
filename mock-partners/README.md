# mock-partners

WireMock stands in for the external partner systems SkyBook integrates with, so every failure
mode (latency, timeouts, 5xx, malformed responses) can be reproduced on demand:

| Partner          | Purpose                             | Added in |
|------------------|-------------------------------------|----------|
| GDS ticketing    | Issue / void e-tickets              | Phase 3  |
| CRM              | Customer profile look-ups           | Phase 3  |
| Loyalty          | Accrue / reverse miles              | Phase 3  |

Layout follows WireMock's conventions and is mounted read-only into the container
(`docker compose --profile partners up -d`, admin API at http://localhost:8095/__admin):

- `wiremock/mappings/` - request matchers and stubbed responses (JSON)
- `wiremock/__files/` - response bodies referenced by mappings
