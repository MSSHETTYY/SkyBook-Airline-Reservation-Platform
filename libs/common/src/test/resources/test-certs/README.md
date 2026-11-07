# Test-only TLS material

Throwaway certificates for the automated mutual-TLS tests. They protect nothing: the CA private key
was deleted right after signing, and none of these files are used outside tests.

| File | Purpose |
|------|---------|
| `ca.crt` | Test CA certificate (trust anchor for server and client) |
| `service.crt` / `service.key` | Certificate for `localhost`, used as both server and client identity |
| `truststore.p12` | `ca.crt` as a PKCS12 trust store (password `changeit`) for WireMock |
| `rogue-client.crt` / `.key` | Client certificate signed by a *different* CA; must be rejected |

Regenerate with `infra/certs/generate-dev-certs.sh` (see that script) if they ever expire (2036).
