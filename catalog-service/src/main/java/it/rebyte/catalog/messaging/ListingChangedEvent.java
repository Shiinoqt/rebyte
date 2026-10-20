package it.rebyte.catalog.messaging;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Identifiers, sequence and event time for every listing event. Price changes
 * additionally carry the prior and new price and listing title for messaging's
 * price-drop notification. The sequence is the listing's persisted version,
 * used by the search projection to discard stale deliveries.
 */
public record ListingChangedEvent(
        String listingId,
        long seq,
        Instant occurredAt,
        BigDecimal oldPrice,
        BigDecimal newPrice,
        String listingTitle) {

    public ListingChangedEvent(String listingId, long seq, Instant occurredAt) {
        this(listingId, seq, occurredAt, null, null, null);
    }

    public ListingChangedEvent(String listingId, long seq, Instant occurredAt, BigDecimal oldPrice,
            BigDecimal newPrice) {
        this(listingId, seq, occurredAt, oldPrice, newPrice, null);
    }

}
