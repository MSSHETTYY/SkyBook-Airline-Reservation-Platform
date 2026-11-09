#!/bin/sh
# Writes the SPA's runtime configuration from environment variables when the container starts,
# so one image can be promoted from dev to prod unchanged (only the variables differ).
set -eu

json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
}

target=/usr/share/nginx/html/config.js
cat > "$target" <<EOF
// Generated at container start by 40-skybook-config.sh. Do not edit.
window.__SKYBOOK_CONFIG__ = {
  "VITE_AUTH_MODE": "oidc",
  "VITE_OIDC_AUTHORITY": "$(json_escape "${SKYBOOK_OIDC_AUTHORITY:-}")",
  "VITE_OIDC_CLIENT_ID": "$(json_escape "${SKYBOOK_OIDC_CLIENT_ID:-}")",
  "VITE_STRIPE_PUBLISHABLE_KEY": "$(json_escape "${SKYBOOK_STRIPE_PUBLISHABLE_KEY:-}")"
};
EOF
echo "skybook: runtime config written to $target"
