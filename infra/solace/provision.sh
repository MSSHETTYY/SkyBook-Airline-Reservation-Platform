#!/usr/bin/env bash
# Provisions Solace PubSub+ for SkyBook through the SEMPv2 management API (idempotent: existing objects are
# left as they are). Run once after `docker compose --profile messaging up -d`:
#
#   ./infra/solace/provision.sh
#
# Creates:
#   - client username "skybook" (event-bridge publishes, notification-service consumes)
#   - queue q.notification.dmq            dead-message queue for undeliverable notifications
#   - queue q.notification                durable, exclusive, subscribed to skybook/> ; after 3 failed
#                                         deliveries a message moves to q.notification.dmq
set -euo pipefail

SEMP="${SOLACE_SEMP_URL:-http://localhost:8190/SEMP/v2/config}"
ADMIN_USER="${SOLACE_ADMIN_USER:-admin}"
ADMIN_PASSWORD="${SOLACE_ADMIN_PASSWORD:-solace_admin_dev}"
VPN="${SOLACE_VPN:-default}"
CLIENT_USER="${SOLACE_USERNAME:-skybook}"
CLIENT_PASSWORD="${SOLACE_PASSWORD:-skybook_dev}"

semp() {
  local method="$1" path="$2" body="${3:-}"
  local status
  status=$(curl -s -o /tmp/semp-response.json -w '%{http_code}' -u "$ADMIN_USER:$ADMIN_PASSWORD" \
    -X "$method" -H 'Content-Type: application/json' "$SEMP$path" ${body:+-d "$body"})
  if [[ "$status" == 2* ]]; then
    return 0
  fi
  if grep -q 'ALREADY_EXISTS' /tmp/semp-response.json 2>/dev/null; then
    return 0
  fi
  echo "SEMP $method $path failed with HTTP $status:" >&2
  cat /tmp/semp-response.json >&2
  return 1
}

echo "Waiting for the Solace broker..."
until curl -s -o /dev/null -u "$ADMIN_USER:$ADMIN_PASSWORD" "$SEMP/msgVpns/$VPN"; do sleep 3; done

semp POST "/msgVpns/$VPN/clientUsernames" \
  "{\"clientUsername\":\"$CLIENT_USER\",\"password\":\"$CLIENT_PASSWORD\",\"enabled\":true}"

semp POST "/msgVpns/$VPN/queues" \
  '{"queueName":"q.notification.dmq","accessType":"exclusive","permission":"consume","ingressEnabled":true,"egressEnabled":true}'

semp POST "/msgVpns/$VPN/queues" \
  '{"queueName":"q.notification","accessType":"exclusive","permission":"consume","ingressEnabled":true,
    "egressEnabled":true,"maxRedeliveryCount":3,"deadMsgQueue":"q.notification.dmq","respectTtlEnabled":true}'

semp POST "/msgVpns/$VPN/queues/q.notification/subscriptions" '{"subscriptionTopic":"skybook/>"}'

echo "Solace provisioned: queue q.notification <- skybook/> (DMQ q.notification.dmq), user $CLIENT_USER"
