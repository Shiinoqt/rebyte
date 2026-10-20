ALTER TABLE orders
    ADD COLUMN provider_order_id VARCHAR(64) CHARACTER SET ascii NULL,
    ADD COLUMN provider_capture_id VARCHAR(64) CHARACTER SET ascii NULL,
    ADD COLUMN cancellation_reason VARCHAR(32) CHARACTER SET ascii NULL;

CREATE TABLE order_inbox_events (
    event_id VARCHAR(100) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    received_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE order_outbox_events (
    id VARCHAR(36) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    event_type VARCHAR(80) CHARACTER SET ascii NOT NULL,
    aggregate_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    KEY idx_order_outbox_unpublished (published_at, occurred_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
