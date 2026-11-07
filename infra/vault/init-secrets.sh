#!/bin/sh
# Seeds the development Vault (dev mode, in-memory) with the secrets the services read at start-up
# through Spring Cloud Vault (profile "vault"). Runs once per `docker compose --profile secrets up`.
#
# Dev mode loses everything on restart, so the encryption key below is a FIXED development key:
# data encrypted in an earlier run stays readable. In any real environment Vault runs with persistent
# storage and keys are generated once (and rotated) - see ADR-0011.
set -eu

: "${VAULT_ADDR:?VAULT_ADDR must be set}"
: "${VAULT_TOKEN:?VAULT_TOKEN must be set}"

until vault status >/dev/null 2>&1; do
  echo "waiting for vault..."
  sleep 1
done

vault kv put secret/booking-service \
  spring.datasource.password="${BOOKING_DB_PASSWORD:-booking_dev}" \
  spring.security.oauth2.client.registration.skybook-services.client-secret="${BOOKING_CLIENT_SECRET:-booking-service-dev-secret}" \
  skybook.crypto.active-key=v1 \
  skybook.crypto.keys.v1="${CRYPTO_KEY_V1:-c2t5Ym9vay1kZXYtdmF1bHQta2V5LTMyLWJ5dGVzISE=}"

vault kv put secret/inventory-service \
  spring.datasource.password="${INVENTORY_DB_PASSWORD:-inventory_dev}" \
  spring.data.redis.password="${REDIS_PASSWORD:-redis_dev}"

vault kv put secret/payment-service \
  spring.datasource.password="${PAYMENT_DB_PASSWORD:-payment_dev}" \
  skybook.payment.stripe.secret-key="${STRIPE_SECRET_KEY:-}" \
  skybook.payment.stripe.webhook-secret="${STRIPE_WEBHOOK_SECRET:-}"

vault kv put secret/api-gateway \
  spring.data.redis.password="${REDIS_PASSWORD:-redis_dev}"

echo "SkyBook development secrets written to Vault."
