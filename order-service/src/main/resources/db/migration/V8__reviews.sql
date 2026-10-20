-- Surrogate id plus the order as alternate identifier (V.Review.onePerOrder): one review per order ever (D-31).
-- Rows are immutable (D-32) and are kept after a refund of their order (D-33).
CREATE TABLE reviews (
    id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    order_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    buyer_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    seller_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    listing_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    listing_title VARCHAR(255) NOT NULL,
    rating TINYINT NOT NULL,
    comment VARCHAR(2000) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_order (order_id),
    KEY idx_review_seller_created (seller_sub, created_at, id),
    CONSTRAINT chk_review_rating CHECK (rating BETWEEN 0 AND 5)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
