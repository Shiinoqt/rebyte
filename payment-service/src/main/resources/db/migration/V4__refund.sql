-- payment_db.payments full-refund columns (03-10-PLAN.md Task 2, D-27).
--
-- refund_reason: the mandatory admin-supplied reason, persisted once with
-- the refund claim (never overwritten by a later retry).
--
-- refund_request_id: a deterministic PayPal-Request-Id persisted BEFORE the
-- refund call and replayed verbatim on any retry — never regenerated,
-- mirroring capture_request_id.
--
-- refund_dispatched_at: durable one-attempt fence, set before the PayPal
-- refund request so a repeated or racing internal call cannot issue a
-- second provider refund call, mirroring capture_dispatched_at. Only one
-- full-amount (empty-body) refund is ever sent per capture; PAYMENT.CAPTURE.REFUNDED
-- webhook delivery (Plan 08) alone advances provider_capture_status to
-- REFUNDED and emits payment.refunded — this claim never does.
ALTER TABLE payments
    ADD COLUMN refund_reason        VARCHAR(500) NULL,
    ADD COLUMN refund_request_id    VARCHAR(64) CHARACTER SET ascii NULL,
    ADD COLUMN refund_dispatched_at DATETIME(6) NULL;
