package it.rebyte.order.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * order-service's own copy of catalog-service's {@code listing.updated}/{@code listing.removed}
 * outbox payload (D-25: no shared event DTOs between services). The listener ignores
 * {@code oldPrice}/{@code newPrice} and {@code seq} — the sync decision always re-reads the
 * current listing state through {@code GET /internal/listings/{id}} rather than trusting the
 * event body (03.1-CONTEXT.md D-01).
 */
public record ListingLifecycleEvent(String listingId, long seq, Instant occurredAt, BigDecimal oldPrice,
        BigDecimal newPrice) {
}
