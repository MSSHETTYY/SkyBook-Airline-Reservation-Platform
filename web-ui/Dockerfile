# syntax=docker/dockerfile:1.7
# Build from the repository root (the OpenAPI specs live in ../api):
#   docker build -f web-ui/Dockerfile -t ghcr.io/<owner>/skybook-web-ui:dev .

# 1. Generate the TypeScript clients from the same specs the services implement (API-first).
FROM openapitools/openapi-generator-cli:v7.27.0 AS api-clients
WORKDIR /work/web-ui
COPY api /work/api
COPY web-ui/openapi-generator ./openapi-generator
RUN java -jar /opt/openapi-generator/modules/openapi-generator-cli/target/openapi-generator-cli.jar \
      batch --clean openapi-generator/inventory.yaml openapi-generator/booking.yaml openapi-generator/payment.yaml

# 2. Type-check, test-free production build (tests run in CI before the image is built).
FROM node:24-alpine AS build
WORKDIR /app
COPY web-ui/package.json web-ui/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --ignore-scripts --no-audit --no-fund
COPY web-ui/ ./
COPY --from=api-clients /work/web-ui/src/api/generated ./src/api/generated
RUN npm run build

# 3. Static files served by unprivileged nginx (runs as uid 101, listens on 8080, read-only friendly).
FROM nginxinc/nginx-unprivileged:1.31-alpine
LABEL org.opencontainers.image.title="skybook-web-ui" \
      org.opencontainers.image.description="SkyBook customer and agent portal" \
      org.opencontainers.image.source="https://github.com/MSSHETTYY/SkyBook-Airline-Reservation-Platform"

# Upstream defaults; override per environment.
ENV SKYBOOK_GATEWAY_URL=http://api-gateway:8080 \
    SKYBOOK_OIDC_AUTHORITY=http://localhost:8180/realms/skybook \
    SKYBOOK_OIDC_ORIGIN=http://localhost:8180 \
    SKYBOOK_OIDC_CLIENT_ID=skybook-web \
    SKYBOOK_STRIPE_PUBLISHABLE_KEY=""

COPY web-ui/docker/default.conf.template /etc/nginx/templates/default.conf.template
COPY --chmod=755 web-ui/docker/40-skybook-config.sh /docker-entrypoint.d/40-skybook-config.sh
COPY --from=build /app/dist /usr/share/nginx/html

# config.js is rewritten at start-up, so only that file is writable by the nginx user.
USER root
RUN chown 101:101 /usr/share/nginx/html/config.js
USER 101

EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --retries=3 CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
