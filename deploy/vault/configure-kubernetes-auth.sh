#!/usr/bin/env sh
# Enables Vault's Kubernetes auth method and creates one role + policy per SkyBook service, so each pod
# can read only secret/<its-name> using its own service-account token (ADR-0011).
#
# Run where `vault` is logged in with admin rights and can reach the cluster API, e.g. inside the Vault pod:
#   kubectl -n vault exec -it vault-0 -- sh -s < deploy/vault/configure-kubernetes-auth.sh
# Environment: SKYBOOK_NAMESPACE (default skybook), VAULT_K8S_HOST (default in-cluster API server).
set -eu

NAMESPACE="${SKYBOOK_NAMESPACE:-skybook}"
K8S_HOST="${VAULT_K8S_HOST:-https://kubernetes.default.svc}"
SERVICES="inventory-service booking-service payment-service notification-service event-bridge api-gateway"

vault auth list | grep -q '^kubernetes/' || vault auth enable kubernetes
# Inside the cluster Vault validates tokens with its own service account (token reviewer).
vault write auth/kubernetes/config kubernetes_host="$K8S_HOST"

vault secrets list | grep -q '^secret/' || vault secrets enable -path=secret kv-v2

for service in $SERVICES; do
  vault policy write "$service" - <<EOF
# $service may read its own secrets and nothing else.
path "secret/data/$service" {
  capabilities = ["read"]
}
path "secret/data/application" {
  capabilities = ["read"]
}
EOF
  vault write "auth/kubernetes/role/$service" \
    bound_service_account_names="$service" \
    bound_service_account_namespaces="$NAMESPACE" \
    policies="$service" \
    ttl=1h max_ttl=4h
  echo "role $service -> policy $service (namespace $NAMESPACE)"
done
