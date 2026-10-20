-- pending_guard equals listing_id while an offer is PENDING, and is NULL otherwise.
-- MySQL treats NULL values as distinct, enforcing one pending offer per buyer and listing.
-- hold_reservation_id identifies the current accept attempt, including while PENDING and after ACCEPTED.
-- order_id is the current live order link; expires_at is set only on acceptance.
CREATE TABLE offers (
    id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    listing_id VARCHAR(36) CHARACTER SET ascii NOT NULL,
    buyer_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    seller_sub VARCHAR(64) CHARACTER SET ascii NOT NULL,
    listing_title VARCHAR(255) NOT NULL,
    asking_price DECIMAL(10,2) NOT NULL,
    amount DECIMAL(10,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    rejection_reason VARCHAR(32) CHARACTER SET ascii NULL,
    pending_guard VARCHAR(36) CHARACTER SET ascii NULL,
    hold_reservation_id VARCHAR(36) CHARACTER SET ascii NULL,
    accept_claimed_at DATETIME(6) NULL,
    expires_at DATETIME(6) NULL,
    order_id VARCHAR(36) CHARACTER SET ascii NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_offer_buyer_pending_guard (buyer_sub, pending_guard),
    KEY idx_offer_listing_status (listing_id, status),
    KEY idx_offer_buyer_created (buyer_sub, created_at, id),
    KEY idx_offer_seller_created (seller_sub, created_at, id),
    KEY idx_offer_status_expires (status, expires_at)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
