-- One report per order ever (D-12); a withdrawn or resolved report remains as history.
CREATE TABLE order_problem (
    id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    order_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    reason VARCHAR(32) CHARACTER SET ascii NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(20) CHARACTER SET ascii NOT NULL,
    outcome VARCHAR(20) CHARACTER SET ascii NULL,
    resolved_by VARCHAR(64) CHARACTER SET ascii NULL,
    resolution_note VARCHAR(1000) NULL,
    created_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_problem_order (order_id),
    KEY idx_order_problem_status_created (status, created_at, id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
