-- seller_sale: local projection of delivered and refunded order events.
-- One row per order with two nullable timestamps makes event replay safe and
-- order-independent: a refund observed before delivery still prevents the
-- sale from being counted. completedSales is a per-request COUNT, like the
-- seller_review reputation aggregates (D-36, D-48).
CREATE TABLE seller_sale (
    order_id     VARCHAR(36) CHARACTER SET ascii NOT NULL,
    seller_sub   VARCHAR(64) CHARACTER SET ascii NOT NULL,
    delivered_at DATETIME(6) NULL,
    refunded_at  DATETIME(6) NULL,
    PRIMARY KEY (order_id),
    INDEX idx_seller_sale_seller_sub (seller_sub)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
