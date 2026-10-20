-- payment_db.payments reconciliation columns (03-10-PLAN.md Task 1, D-24..D-26,
-- D-29). TERMINAL_NO_CHARGE joins OPEN/CAPTURING/CLOSED as a `state` value —
-- the column is already VARCHAR(20), which comfortably fits the new 19-char
-- literal, so no column-width change is needed.
--
-- last_paypal_status: the sweep's or a fencing decision's last observed
-- PayPal order/capture status word or qualified error name, for the D-29
-- stuck-order diagnostic view.
--
-- captured_awaiting_webhook: true from the moment the sweep (or the capture
-- response itself) observes PayPal COMPLETED with no confirmed webhook yet;
-- cleared to false the moment a real webhook confirms the outcome
-- (Payment.markWebhookConfirmed).
--
-- webhook_confirmed_at: set only by a genuine verified webhook, never by the
-- sweep. Excludes a resolved row from the reconciliation "due" query so a
-- fully paid/refunded/denied payment is not swept forever.
ALTER TABLE payments
    ADD COLUMN last_paypal_status        VARCHAR(32) CHARACTER SET ascii NULL,
    ADD COLUMN captured_awaiting_webhook  TINYINT(1) NOT NULL DEFAULT 0,
    ADD COLUMN webhook_confirmed_at       DATETIME(6) NULL;
