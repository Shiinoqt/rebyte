-- license_key is the primary identifier (D-23); order_id is the alternate identifier.
-- Keep revoked rows for audit (D-22); orders.license_key_pattern is the checkout snapshot (D-25).
ALTER TABLE orders ADD COLUMN license_key_pattern VARCHAR(64) CHARACTER SET ascii NULL;

CREATE TABLE licenses (
    license_key VARCHAR(64) CHARACTER SET ascii NOT NULL,
    order_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    buyer_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    seller_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    listing_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    listing_title VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (license_key),
    UNIQUE KEY uk_license_order (order_id),
    KEY idx_license_buyer_issued (buyer_sub, issued_at, order_id),
    KEY idx_license_listing_holder (listing_id, status, buyer_sub)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
