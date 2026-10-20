package it.rebyte.user.messaging;

import java.time.Instant;

/**
 * Payload of the {@code review.created} event (D-47) published by
 * order-service. {@code rating} is 0 to 5 inclusive and boxed so that an
 * absent value is rejected instead of defaulting to 0. {@code reviewId} and
 * {@code sellerSub} must fit the ascii {@code VARCHAR(64)} projection
 * columns.
 */
public record ReviewCreatedEvent(String reviewId, String sellerSub, Byte rating, Instant occurredAt) {
}
