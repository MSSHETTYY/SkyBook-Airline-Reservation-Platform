-- Reference DDL for the messaging tables. Each service copies it into its own Flyway migration so the
-- service owns its schema history (shared migrations would interleave versions across services).

CREATE TABLE outbox_event (
    id              CHAR(36)      PRIMARY KEY,
    topic           VARCHAR(128)  NOT NULL,
    message_key     VARCHAR(128)  NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    correlation_id  VARCHAR(64),
    payload         TEXT          NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    published_at    TIMESTAMP(6),
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(512)
);

-- Relay: "unpublished rows, oldest first".
CREATE INDEX ix_outbox_unpublished ON outbox_event (published_at, created_at);

CREATE TABLE processed_event (
    consumer      VARCHAR(64)   NOT NULL,
    -- Domain event UUIDs, or external ids such as Stripe event ids (evt_...).
    event_id      VARCHAR(64)   NOT NULL,
    processed_at  TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);
