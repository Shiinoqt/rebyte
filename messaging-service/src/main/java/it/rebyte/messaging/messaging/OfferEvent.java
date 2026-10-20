package it.rebyte.messaging.messaging;

import java.math.BigDecimal;
import java.time.Instant;

public record OfferEvent(String eventId, String offerId, String listingId, String listingTitle,
        String buyerSub, String sellerSub, BigDecimal amount, BigDecimal askingPrice, String status,
        String rejectionReason, Instant expiresAt, Instant occurredAt) {
}
