-- booking-service schema (MariaDB). Written in the subset of MariaDB SQL that H2 (MODE=MariaDB) also
-- accepts, so the same migrations run in fast tests and in MariaDB Testcontainers integration tests.
--
-- Personal data columns (contact_email, first_name, last_name, date_of_birth) are sized for ciphertext:
-- they are encrypted by the application before they reach the database (see ADR on PII encryption).

CREATE TABLE booking (
    id                     BIGINT AUTO_INCREMENT PRIMARY KEY,
    reference              CHAR(6)        NOT NULL,
    customer_id            VARCHAR(64)    NOT NULL,
    contact_email          VARCHAR(512)   NOT NULL,
    status                 VARCHAR(32)    NOT NULL,
    flight_id              BIGINT         NOT NULL,
    flight_number          VARCHAR(8)     NOT NULL,
    origin                 CHAR(3)        NOT NULL,
    destination            CHAR(3)        NOT NULL,
    departure_time         DATETIME(6)    NOT NULL,
    total_amount           DECIMAL(12, 2) NOT NULL,
    currency               CHAR(3)        NOT NULL,
    hold_id                VARCHAR(36),
    hold_expires_at        DATETIME(6),
    payment_id             VARCHAR(64),
    payment_client_secret  VARCHAR(512),
    amount_paid            DECIMAL(12, 2) NOT NULL DEFAULT 0,
    amount_refunded        DECIMAL(12, 2) NOT NULL DEFAULT 0,
    idempotency_key        VARCHAR(64)    NOT NULL,
    request_fingerprint    VARCHAR(64)    NOT NULL,
    created_at             DATETIME(6)    NOT NULL,
    updated_at             DATETIME(6)    NOT NULL,
    version                BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_booking_reference UNIQUE (reference),
    CONSTRAINT uq_booking_customer_idempotency UNIQUE (customer_id, idempotency_key),
    CONSTRAINT ck_booking_refund CHECK (amount_refunded <= amount_paid)
);

-- "My bookings", newest first.
CREATE INDEX ix_booking_customer_created ON booking (customer_id, created_at);
-- Expiry sweeper: PENDING_PAYMENT bookings whose hold ran out.
CREATE INDEX ix_booking_status_hold_expiry ON booking (status, hold_expires_at);
-- Agent search by flight and status.
CREATE INDEX ix_booking_flight_status ON booking (flight_id, status);

CREATE TABLE booking_passenger (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    booking_id     BIGINT         NOT NULL,
    position       INT            NOT NULL,
    first_name     VARCHAR(512)   NOT NULL,
    last_name      VARCHAR(512)   NOT NULL,
    date_of_birth  VARCHAR(512)   NOT NULL,
    seat_number    VARCHAR(4)     NOT NULL,
    seat_price     DECIMAL(12, 2) NOT NULL,
    ticket_number  VARCHAR(20),
    CONSTRAINT fk_passenger_booking FOREIGN KEY (booking_id) REFERENCES booking (id) ON DELETE CASCADE,
    CONSTRAINT uq_passenger_position UNIQUE (booking_id, position)
);

-- A pending date change (at most one per booking).
CREATE TABLE booking_pending_change (
    booking_id           BIGINT         PRIMARY KEY,
    new_flight_id        BIGINT         NOT NULL,
    new_flight_number    VARCHAR(8)     NOT NULL,
    new_origin           CHAR(3)        NOT NULL,
    new_destination      CHAR(3)        NOT NULL,
    new_departure_time   DATETIME(6)    NOT NULL,
    hold_id              VARCHAR(36)    NOT NULL,
    hold_expires_at      DATETIME(6)    NOT NULL,
    new_seats            VARCHAR(512)   NOT NULL,
    amount_due           DECIMAL(12, 2) NOT NULL,
    payment_id           VARCHAR(64),
    CONSTRAINT fk_change_booking FOREIGN KEY (booking_id) REFERENCES booking (id) ON DELETE CASCADE
);
