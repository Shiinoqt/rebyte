package it.rebyte.messaging.messaging;

import java.time.Instant;

/** Hand-copied review.created payload from order-service; it carries no eventId and no reviewer sub. */
public record ReviewEvent(String reviewId, String sellerSub, Integer rating, Instant occurredAt) {
}
