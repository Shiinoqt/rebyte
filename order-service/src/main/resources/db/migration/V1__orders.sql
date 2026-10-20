-- order_db.orders (03-CONTEXT.md D-01, D-04, D-09, D-10, D-13, D-16, D-17,
-- D-24, D-35; ORD-01..04, PAY-01).
--
-- id: a UUID string minted by order-service itself (UUID.randomUUID()),
-- never DB-generated. It doubles as the reservationId sent to catalog-
-- service's reserve contract (D-17), and will later double as the PayPal
-- purchase-unit custom_id correlation key (D-36, payment-service).
--
-- active_listing_guard: nullable, set to listing_id only while a HARDWARE
-- order is live, cleared to NULL on cancellation/refund (D-16). MySQL's
-- unique index treats every NULL as distinct, so any number of terminal or
-- SOFTWARE orders coexist under this constraint; only one row may hold a
-- given listing_id as its guard value at a time. That single-writer race is
-- the entire mechanism behind V.HardwareListing.singleItem and criterion 4
-- (03-CONTEXT.md D-19) — no Redis lock is involved (D-18).
--
-- checkout_phase: an operation gate separate from `status` (D-16 amendment,
-- see Order.java's Javadoc for the full state meaning). The guarded
-- CREATING -> READY transition is what stops a slow/failed payment-create
-- call from reviving an order some other path already moved to CANCELLED.
--
-- capture_operation_id: the durable claim id granted exactly once per order
-- by POST /internal/orders/{id}/capture-authorizations (consumed by
-- payment-service, a later plan) — repeat calls with the same id are
-- idempotent, a different id while already CAPTURE_AUTHORIZED is denied.
--
-- (buyer_sub, idempotency_key): D-04 — a retry of POST /orders with the
-- same header returns the original order. request_fingerprint is a SHA-256
-- hex digest of the normalized request body, so the same key reused with a
-- DIFFERENT payload is rejected instead of silently returning an unrelated
-- order.
--
-- item_price/shipping_cost/price_paid: DECIMAL(10,2) end to end (D-35) —
-- BigDecimal in Java, never a floating-point type, and order-service itself
-- also rejects any snapshot or client-supplied total above two decimal
-- digits with 422 rather than silently rounding.
--
-- Address columns (recipient_name..country) are nullable and populated if
-- and only if listing_type = HARDWARE (V.Order.shippingAddressIffHardware,
-- docs/rebyte_progettazione.md §5.1) — a snapshot on the order, never the
-- buyer's profile, so it stays what was declared at purchase even if the
-- buyer later moves.
CREATE TABLE orders (
    id                    VARCHAR(36) CHARACTER SET ascii NOT NULL,
    buyer_sub             VARCHAR(64) CHARACTER SET ascii NOT NULL,
    seller_sub            VARCHAR(64) CHARACTER SET ascii NOT NULL,
    listing_id            VARCHAR(36) CHARACTER SET ascii NOT NULL,
    listing_type          VARCHAR(20) NOT NULL,
    listing_title         VARCHAR(255) NOT NULL,
    item_price            DECIMAL(10,2) NOT NULL,
    shipping_cost         DECIMAL(10,2) NOT NULL,
    price_paid            DECIMAL(10,2) NOT NULL,
    payment_method        VARCHAR(20) NOT NULL,
    status                VARCHAR(20) NOT NULL,
    checkout_phase        VARCHAR(20) NOT NULL,
    active_listing_guard  VARCHAR(36) CHARACTER SET ascii NULL,
    reservation_id        VARCHAR(36) CHARACTER SET ascii NULL,
    approval_url          VARCHAR(500) NULL,
    capture_operation_id  VARCHAR(64) CHARACTER SET ascii NULL,
    idempotency_key       VARCHAR(128) NOT NULL,
    request_fingerprint   VARCHAR(64) CHARACTER SET ascii NOT NULL,
    created_at            DATETIME(6) NOT NULL,
    expiry_at             DATETIME(6) NOT NULL,
    recipient_name        VARCHAR(150) NULL,
    street                VARCHAR(200) NULL,
    city                  VARCHAR(100) NULL,
    postal_code           VARCHAR(20) NULL,
    country               VARCHAR(2) CHARACTER SET ascii NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_active_listing_guard (active_listing_guard),
    UNIQUE KEY uk_order_buyer_idempotency (buyer_sub, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
