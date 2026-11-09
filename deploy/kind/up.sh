#!/usr/bin/env bash
# One-command local Kubernetes deployment (Git Bash / WSL / macOS / Linux):
#   1. kind cluster with ingress-nginx and cert-manager
#   2. builds the SkyBook images and side-loads them into the cluster (no registry needed)
#   3. helm upgrade --install with values-kind.yaml, then the helm smoke test
#
# Prerequisites: Docker Desktop, kind, kubectl, helm; the Compose infrastructure running on the host:
#   docker compose --profile core --profile messaging --profile auth --profile notification --profile partners up -d
# (on an 8 GB machine run fewer services: set SKYBOOK_SERVICES, e.g. "inventory-service api-gateway web-ui").
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
CLUSTER=skybook
NAMESPACE=skybook
TAG=dev
SERVICES="${SKYBOOK_SERVICES:-inventory-service booking-service payment-service notification-service event-bridge api-gateway web-ui}"
INGRESS_NGINX_VERSION=controller-v1.13.3
CERT_MANAGER_VERSION=v1.19.1

need() { command -v "$1" >/dev/null || { echo "missing prerequisite: $1" >&2; exit 1; }; }
need docker; need kind; need kubectl; need helm

if ! kind get clusters | grep -qx "$CLUSTER"; then
  kind create cluster --config "$ROOT/deploy/kind/kind-config.yaml"
fi
kubectl config use-context "kind-$CLUSTER"

echo ">> ingress-nginx"
kubectl apply -f "https://raw.githubusercontent.com/kubernetes/ingress-nginx/$INGRESS_NGINX_VERSION/deploy/static/provider/kind/deploy.yaml"

echo ">> cert-manager"
helm repo add jetstack https://charts.jetstack.io --force-update >/dev/null
helm upgrade --install cert-manager jetstack/cert-manager --namespace cert-manager --create-namespace \
  --version "$CERT_MANAGER_VERSION" --set crds.enabled=true --wait

kubectl -n ingress-nginx wait --for=condition=ready pod -l app.kubernetes.io/component=controller --timeout=180s

echo ">> images"
for service in $SERVICES; do
  image="ghcr.io/msshettyy/skybook-$service:$TAG"
  if [ "$service" = "web-ui" ]; then
    docker build -f "$ROOT/web-ui/Dockerfile" -t "$image" "$ROOT"
  else
    docker build -f "$ROOT/services/Dockerfile" --build-arg SERVICE="$service" -t "$image" "$ROOT"
  fi
  kind load docker-image "$image" --name "$CLUSTER"
done

echo ">> skybook"
enabled_flags=()
for service in inventory-service booking-service payment-service notification-service event-bridge api-gateway web-ui; do
  case " $SERVICES " in
    *" $service "*) ;;
    *) enabled_flags+=(--set "services.$service.enabled=false") ;;
  esac
done
helm upgrade --install skybook "$ROOT/deploy/helm/skybook" --namespace "$NAMESPACE" --create-namespace \
  -f "$ROOT/deploy/helm/skybook/values-kind.yaml" "${enabled_flags[@]}" --wait --timeout 10m

case " $SERVICES " in
  *" api-gateway "*" web-ui "*|*" web-ui "*" api-gateway "*) helm test skybook --namespace "$NAMESPACE" ;;
esac

echo
echo "SkyBook is running at http://localhost/ (sign in as customer@skybook.test, see the README)."
echo "Remove everything with: kind delete cluster --name $CLUSTER"
