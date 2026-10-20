package it.rebyte.messaging.client;

import java.math.BigDecimal;

/** Subset of catalog-service's internal listing view used by the conversation start rules. */
public record ListingAvailability(String id, String sellerSub, String listingTitle, BigDecimal price, String type,
        boolean available) {
}
