package it.rebyte.order.messaging;

import java.time.Instant;

/** Wire contract consumed by user-service's seller_review projection; keys and types must not change. */
public record ReviewCreatedEvent(String reviewId, String sellerSub, byte rating, Instant occurredAt) {
}
