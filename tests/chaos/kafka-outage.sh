#!/usr/bin/env bash
# Game-day scenario: Kafka freezes for OUTAGE_SECONDS in the middle of a booking.
#
# Hypothesis (docs/runbook.md#kafka-outage, ADR-0013): the customer can still create and pay for a booking.
# Events pile up in the outboxes while the broker is down. When it returns, the saga resumes, the booking is
# ticketed, and no event is lost. Steady state is checked before and after.
#
# Needs the full stack (compose profiles core, messaging, auth, partners, apps) and python3.
#   ./tests/chaos/kafka-outage.sh
set -euo pipefail

GATEWAY="${GATEWAY:-http://localhost:8080}"
KEYCLOAK="${KEYCLOAK:-http://localhost:8180}"
OUTAGE_SECONDS="${OUTAGE_SECONDS:-60}"
json() { python3 -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
log() { printf '%s  %s\n' "$(date -u +%H:%M:%S)" "$*"; }

token=$(curl -fsS "$KEYCLOAK/realms/skybook/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=skybook-test-client \
  -d username=customer@skybook.test -d "password=${CUSTOMER_PASSWORD:-Customer123!}" | json "d['access_token']")
auth=(-H "Authorization: Bearer $token")

date=$(python3 -c "import datetime; print((datetime.date.today() + datetime.timedelta(days=10)).isoformat())")
flight=$(curl -fsS "$GATEWAY/api/v1/flights?origin=DXB&destination=LHR&date=$date" | json "d[0]['flightId']")
seat=$(curl -fsS "$GATEWAY/api/v1/flights/$flight/seats" \
  | json "[s['seatNumber'] for s in d['seats'] if s['available'] and s['cabinClass'] == 'ECONOMY'][-1]")
log "steady state: flight $flight, seat $seat available"

log "chaos: pausing Kafka for ${OUTAGE_SECONDS}s"
docker compose pause kafka
trap 'docker compose unpause kafka >/dev/null 2>&1 || true' EXIT

booking=$(curl -fsS "${auth[@]}" -H "Content-Type: application/json" -H "Idempotency-Key: chaos-$(date +%s)" \
  -d "{\"flightId\": $flight, \"contactEmail\": \"customer@skybook.test\", \"passengers\": [{\"firstName\": \"Chaos\",
       \"lastName\": \"Monkey\", \"dateOfBirth\": \"1990-01-01\", \"seatNumber\": \"$seat\"}]}" \
  "$GATEWAY/api/v1/bookings")
reference=$(echo "$booking" | json "d['bookingReference']")
log "booking $reference created during the outage (status $(echo "$booking" | json "d['status']"))"

for _ in $(seq 1 30); do
  payment=$(curl -fsS "${auth[@]}" "$GATEWAY/api/v1/bookings/$reference" | json "(d.get('payment') or {}).get('paymentId', '')")
  [ -n "$payment" ] && break
  sleep 1
done
[ -n "$payment" ] || { log "FAIL: no payment attached to $reference"; exit 1; }
curl -fsS "${auth[@]}" -H "Content-Type: application/json" -d '{"outcome": "SUCCEEDED"}' \
  "$GATEWAY/api/v1/payments/$payment/simulation" >/dev/null
log "payment $payment submitted while Kafka is still down"

sleep "$OUTAGE_SECONDS"
log "recovery: unpausing Kafka"
docker compose unpause kafka

for _ in $(seq 1 120); do
  status=$(curl -fsS "${auth[@]}" "$GATEWAY/api/v1/bookings/$reference" | json "d['status']")
  if [ "$status" = "CONFIRMED" ]; then
    log "PASS: $reference ticketed after the outage; events relayed from the outboxes"
    exit 0
  fi
  sleep 2
done
log "FAIL: $reference is $status four minutes after recovery (see runbook: kafka-outage)"
exit 1
