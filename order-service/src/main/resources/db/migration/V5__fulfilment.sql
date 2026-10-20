ALTER TABLE orders
    ADD COLUMN paid_at DATETIME(6) NULL,
    ADD COLUMN shipped_at DATETIME(6) NULL,
    ADD COLUMN delivered_at DATETIME(6) NULL,
    ADD COLUMN carrier VARCHAR(20) NULL,
    ADD COLUMN tracking_code VARCHAR(64) NULL,
    ADD COLUMN refund_requested_at DATETIME(6) NULL,
    ADD INDEX idx_orders_ship_by (status, listing_type, paid_at),
    ADD INDEX idx_orders_auto_deliver (status, shipped_at);
