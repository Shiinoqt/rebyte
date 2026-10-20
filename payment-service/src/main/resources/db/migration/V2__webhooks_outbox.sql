CREATE TABLE paypal_webhook_events (
    event_id VARCHAR(100) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    event_type VARCHAR(100) CHARACTER SET ascii NOT NULL,
    received_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE payment_outbox_events (
    id VARCHAR(36) CHARACTER SET ascii NOT NULL PRIMARY KEY,
    event_type VARCHAR(80) CHARACTER SET ascii NOT NULL,
    aggregate_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    payload JSON NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    KEY idx_payment_outbox_unpublished (published_at, occurred_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
