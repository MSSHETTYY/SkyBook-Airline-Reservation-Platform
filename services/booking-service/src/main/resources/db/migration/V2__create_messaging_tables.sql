-- Transactional outbox and idempotent-consumer tables (see libs/messaging, ADR-0013).

CREATE TABLE outbox_event (
    id              CHAR(36)      PRIMARY KEY,
    topic           VARCHAR(128)  NOT NULL,
    message_key     VARCHAR(128)  NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    correlation_id  VARCHAR(64),
    payload         TEXT          NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    published_at    DATETIME(6),
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(512)
);

-- Relay: "unpublished rows, oldest first".
CREATE INDEX ix_outbox_unpublished ON outbox_event (published_at, created_at);

CREATE TABLE processed_event (
    consumer      VARCHAR(64)   NOT NULL,
    -- Domain event UUIDs, or external ids such as Stripe event ids (evt_...).
    event_id      VARCHAR(64)   NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
