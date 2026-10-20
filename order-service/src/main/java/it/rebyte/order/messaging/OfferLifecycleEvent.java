package it.rebyte.order.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.RejectionReason;

public record OfferLifecycleEvent(String eventId, String offerId, String listingId, String listingTitle,
        String buyerSub, String sellerSub, BigDecimal amount, BigDecimal askingPrice, OfferStatus status,
        RejectionReason rejectionReason, Instant expiresAt, Instant occurredAt) {
    public static OfferLifecycleEvent from(String eventType, Offer offer) {
        return new OfferLifecycleEvent(UUID.randomUUID().toString(), offer.getId(), offer.getListingId(),
                offer.getListingTitle(), offer.getBuyerSub(), offer.getSellerSub(), offer.getAmount(),
                offer.getAskingPrice(), offer.getStatus(),
                RebyteExchanges.OFFER_REJECTED.equals(eventType) ? offer.getRejectionReason() : null,
                RebyteExchanges.OFFER_ACCEPTED.equals(eventType) ? offer.getExpiresAt() : null, Instant.now());
    }
}
