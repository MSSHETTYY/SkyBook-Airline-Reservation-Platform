#!/usr/bin/env bash
# Runs once, on first start of an empty MariaDB data volume (docker-entrypoint-initdb.d).
#
# Database-per-service on a shared server: each service gets its own schema and its own user,
# with privileges on that schema only. No service can read another service's tables.
set -euo pipefail

: "${BOOKING_DB_PASSWORD:?BOOKING_DB_PASSWORD must be set}"
: "${PAYMENT_DB_PASSWORD:?PAYMENT_DB_PASSWORD must be set}"

mariadb --user=root --password="${MARIADB_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS booking CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS payment CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

CREATE USER IF NOT EXISTS 'booking_app'@'%' IDENTIFIED BY '${BOOKING_DB_PASSWORD}';
CREATE USER IF NOT EXISTS 'payment_app'@'%' IDENTIFIED BY '${PAYMENT_DB_PASSWORD}';

-- Schema owners run Flyway migrations at startup, hence DDL rights on their own schema.
GRANT ALL PRIVILEGES ON booking.* TO 'booking_app'@'%';
GRANT ALL PRIVILEGES ON payment.* TO 'payment_app'@'%';
FLUSH PRIVILEGES;
SQL

echo "SkyBook: created schemas 'booking' and 'payment' with dedicated users."
