-- payment-service schema (MariaDB). Portable to H2 (MODE=MariaDB) for fast tests.
-- Card data never reaches SkyBook (Stripe Elements), so nothing here is in PCI-DSS cardholder-data scope.

CREATE TABLE payment (
    id                   VARCHAR(40)    PRIMARY KEY,
    booking_reference    CHAR(6)        NOT NULL,
    purpose              VARCHAR(16)    NOT NULL,
    status               VARCHAR(20)    NOT NULL,
    amount               DECIMAL(12, 2) NOT NULL,
    currency             CHAR(3)        NOT NULL,
    amount_refunded      DECIMAL(12, 2) NOT NULL DEFAULT 0,
    provider_payment_id  VARCHAR(64)    NOT NULL,
    client_secret        VARCHAR(512),
    failure_reason       VARCHAR(255),
    idempotency_key      VARCHAR(64)    NOT NULL,
    request_fingerprint  VARCHAR(128)   NOT NULL,
    -- Correlation ID of the request that created the payment; reused for webhook-driven events so a
    -- booking's whole saga shares one correlation ID even though Stripe calls back asynchronously.
    correlation_id       VARCHAR(64),
    created_at           DATETIME(6)    NOT NULL,
    updated_at           DATETIME(6)    NOT NULL,
    version              BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT uq_payment_provider_id UNIQUE (provider_payment_id),
    CONSTRAINT uq_payment_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_payment_refund CHECK (amount_refunded <= amount),
    CONSTRAINT ck_payment_status CHECK (status IN ('REQUIRES_PAYMENT', 'SUCCEEDED', 'CANCELLED'))
);

-- Agent look-ups and the booking's payment history.
CREATE INDEX ix_payment_booking ON payment (booking_reference, created_at);

CREATE TABLE refund (
    id                  VARCHAR(40)    PRIMARY KEY,
    payment_id          VARCHAR(40)    NOT NULL,
    provider_refund_id  VARCHAR(64)    NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    amount              DECIMAL(12, 2) NOT NULL,
    reason              VARCHAR(32)    NOT NULL,
    idempotency_key     VARCHAR(64)    NOT NULL,
    created_at          DATETIME(6)    NOT NULL,
    updated_at          DATETIME(6)    NOT NULL,
    version             BIGINT         NOT NULL DEFAULT 0,
    CONSTRAINT fk_refund_payment FOREIGN KEY (payment_id) REFERENCES payment (id),
    CONSTRAINT uq_refund_provider_id UNIQUE (provider_refund_id),
    CONSTRAINT uq_refund_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_refund_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED'))
);

CREATE INDEX ix_refund_payment ON refund (payment_id);
