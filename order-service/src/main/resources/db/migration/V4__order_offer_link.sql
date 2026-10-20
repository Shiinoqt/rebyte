-- V4: orders.offer_id links a checkout to the accepted offer it converted
-- from (D-07, D-12). reservation_id is now the persisted identity of the
-- catalog hold an order owns: this order's own id for buy-it-now (set at
-- construction, matching the pre-V4 recomputed value backfilled below), or
-- the accepted offer's hold_reservation_id once Order.linkOffer runs.
-- Cleared to null while an in-hold offer checkout is cancelled, so the
-- release event carries no reservation (D-08, D-21).
ALTER TABLE orders ADD COLUMN offer_id VARCHAR(36) CHARACTER SET ascii NULL;

UPDATE orders SET reservation_id = id
WHERE listing_type = 'HARDWARE' AND reservation_id IS NULL;
