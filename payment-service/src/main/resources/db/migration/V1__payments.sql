-- payment_db.payments (03-CONTEXT.md D-01, D-02, D-06, D-21, D-23, D-36;
-- PAY-01, PAY-02).
--
-- order_id: order-service's own order id (never DB-generated here) is the
-- primary key. Every endpoint this module exposes is addressed by order id
-- (POST /internal/payments, POST /payments/{orderId}/capture, POST
-- /internal/payments/{orderId}/close); D-01's synchronous flow creates
-- exactly one Payment per order.
--
-- buyer_sub: immutable, carried verbatim from order-service's authenticated
-- order snapshot at creation. POST /payments/{orderId}/capture compares the
-- JWT subject against this column, never against anything client-supplied
-- (T-03-10).
--
-- create_request_id: a deterministic PayPal-Request-Id persisted BEFORE the
-- first Orders v2 create call and replayed verbatim only within the short
-- local retry budget — never regenerated (D-06). Older unknown outcomes
-- require reconciliation because no provider retention window was proven.
--
-- state: payment_db's own operation gate (OPEN/CAPTURING/CLOSED),
-- deliberately separate from order-service's checkout_phase — the two
-- services never share a state machine or a database. See
-- Payment.java/PaymentState.java for the full state meaning.
--
-- capture_operation_id: payment-service's own durable claim id, minted
-- exactly once per order by the guarded OPEN -> CAPTURING transition and
-- sent to order-service's POST /internal/orders/{id}/capture-authorizations
-- as the operationId (Task 2). A repeated capture request for the same
-- order reuses this value rather than minting a second one.
--
-- capture_request_id: a second, independent PayPal-Request-Id for the
-- provider capture call, persisted with capture_operation_id. The request
-- is never sent again automatically because the sandbox spike established
-- no safe provider replay window.
-- capture_dispatched_at: durable one-attempt fence, set before the PayPal
-- request so concurrent or restarted workers cannot issue another charge.
--
-- provider_capture_id / provider_capture_status: PayPal's own capture id
-- and its last known status, recorded only after a real provider capture
-- response. Their presence is also what stops a retried capture request
-- from calling PayPal a second time once a capture already succeeded.
CREATE TABLE payments (
    order_id                 VARCHAR(36) CHARACTER SET ascii NOT NULL,
    buyer_sub                VARCHAR(64) CHARACTER SET ascii NOT NULL,
    item_price                DECIMAL(10,2) NOT NULL,
    shipping_cost             DECIMAL(10,2) NOT NULL,
    price_paid                DECIMAL(10,2) NOT NULL,
    listing_title             VARCHAR(255) NOT NULL,
    method                    VARCHAR(20) NOT NULL,
    state                     VARCHAR(20) NOT NULL,
    provider_order_id         VARCHAR(64) CHARACTER SET ascii NULL,
    create_request_id         VARCHAR(64) CHARACTER SET ascii NOT NULL,
    approval_url              VARCHAR(500) NULL,
    capture_operation_id      VARCHAR(64) CHARACTER SET ascii NULL,
    capture_request_id        VARCHAR(64) CHARACTER SET ascii NULL,
    capture_dispatched_at     DATETIME(6) NULL,
    provider_capture_id       VARCHAR(64) CHARACTER SET ascii NULL,
    provider_capture_status   VARCHAR(32) CHARACTER SET ascii NULL,
    created_at                DATETIME(6) NOT NULL,
    PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
