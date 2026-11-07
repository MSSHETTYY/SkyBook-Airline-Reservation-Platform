#!/usr/bin/env bash
# Generates a local development CA and one TLS certificate per SkyBook service, for mutual TLS between
# services (see ADR-0010). Output: infra/certs/out/ (git-ignored).
#
#   ./infra/certs/generate-dev-certs.sh            # uses mkcert if installed, otherwise openssl
#   SKYBOOK_CERT_TOOL=openssl ./infra/certs/generate-dev-certs.sh
#
# On Kubernetes, cert-manager issues these certificates instead (deploy/helm, Phase 6).
set -euo pipefail
# Git Bash on Windows would otherwise rewrite '/O=...' subjects into Windows paths.
export MSYS_NO_PATHCONV=1

OUT_DIR="${SKYBOOK_CERT_DIR:-$(cd "$(dirname "$0")" && pwd)/out}"
SERVICES=(api-gateway inventory-service booking-service payment-service notification-service)
DAYS=825
TOOL="${SKYBOOK_CERT_TOOL:-$(command -v mkcert >/dev/null 2>&1 && echo mkcert || echo openssl)}"

mkdir -p "$OUT_DIR"
cd "$OUT_DIR"

if [[ "$TOOL" == "mkcert" ]]; then
  # mkcert keeps its CA in the user profile and can install it into the OS/browser trust stores.
  CAROOT="$(mkcert -CAROOT)"
  cp "$CAROOT/rootCA.pem" ca.crt
  for service in "${SERVICES[@]}"; do
    mkcert -cert-file "$service.crt" -key-file "$service.key" "$service" localhost 127.0.0.1 >/dev/null
  done
else
  if [[ ! -f ca.key ]]; then
    openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -days 3650 \
      -keyout ca.key -out ca.crt -subj "/O=SkyBook Dev/CN=SkyBook Dev Root CA" \
      -addext "basicConstraints=critical,CA:TRUE" -addext "keyUsage=critical,keyCertSign,cRLSign" 2>/dev/null
  fi
  for service in "${SERVICES[@]}"; do
    openssl req -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes \
      -keyout "$service.key" -out "$service.csr" -subj "/O=SkyBook Dev/CN=$service" 2>/dev/null
    # A temporary extension file, not process substitution: native Windows openssl cannot read /dev/fd paths.
    {
      echo "subjectAltName=DNS:$service,DNS:localhost,IP:127.0.0.1"
      echo "extendedKeyUsage=serverAuth,clientAuth"
      echo "keyUsage=critical,digitalSignature"
    } > "$service.ext"
    openssl x509 -req -in "$service.csr" -CA ca.crt -CAkey ca.key -CAcreateserial -days "$DAYS" \
      -out "$service.crt" -extfile "$service.ext" 2>/dev/null
    rm -f "$service.csr" "$service.ext"
  done
fi

# Java services read PEM directly through Spring Boot SSL bundles; a PKCS12 trust store is also produced
# for tools that need one (e.g. WireMock). curl can use ca.crt with --cacert.
rm -f truststore.p12
keytool -importcert -noprompt -storetype PKCS12 -keystore truststore.p12 -storepass changeit \
  -alias skybook-dev-ca -file ca.crt >/dev/null

echo "Certificates written to $OUT_DIR (tool: $TOOL)"
