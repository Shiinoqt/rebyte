package it.rebyte.messaging.messaging;

import java.math.BigDecimal;
import java.time.Instant;

public record ListingPriceEvent(String listingId, long seq, Instant occurredAt, BigDecimal oldPrice,
        BigDecimal newPrice, String listingTitle) {
}
