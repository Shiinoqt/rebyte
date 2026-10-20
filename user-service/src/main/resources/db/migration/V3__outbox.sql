-- V3: transactional outbox (D-14, D-15, D-17, D-18, D-19, D-20; SOCL-01,
-- SOCL-02). The publication path every domain event leaves through — no
-- service method calls the broker directly (T-04-01).
--
-- id: BIGINT AUTO_INCREMENT is the outbox row's own surrogate, deliberately
-- NOT the binding model's `sub`-keyed convention used elsewhere in this
-- schema (admin, developer_profile) — this is an infrastructure table, not
-- a class from docs/rebyte_progettazione.md ch. 10-12.
--
-- event_id: the identifier carried IN the payload (D-21), VARCHAR(64) ascii
-- like every other identifier column in this schema, UNIQUE so a consumer
-- can dedupe on it and so DomainEventRecorder cannot accidentally record
-- the same logical event twice.
--
-- published_at: nullable ON PURPOSE (D-19) — a published row is retained
-- with its publication timestamp, not deleted at publish time. Only
-- OutboxPurgeJob removes rows, and only once they are past the retention
-- window. This is the evidence a duplicate-publish or missing-event
-- investigation needs to read.
--
-- idx_outbox_event_claim: covers the claim predicate
-- (`published_at IS NULL ORDER BY id ASC`) so OutboxRelay's
-- `SELECT ... FOR UPDATE SKIP LOCKED` (D-14) finds the oldest unpublished
-- rows without a table scan. published_at leads the index because the
-- claim query filters on it; id is the tiebreaker the ORDER BY needs.
CREATE TABLE outbox_event (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id     VARCHAR(64) CHARACTER SET ascii NOT NULL,
    routing_key  VARCHAR(64) NOT NULL,
    payload      JSON NOT NULL,
    occurred_at  DATETIME(6) NOT NULL,
    published_at DATETIME(6) NULL,
    CONSTRAINT uq_outbox_event_event_id UNIQUE (event_id),
    INDEX idx_outbox_event_claim (published_at, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
