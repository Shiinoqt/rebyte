package it.rebyte.order.dto;

import java.math.BigDecimal;
import java.time.Instant;

import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.RejectionReason;

public record OfferView(String id, String listingId, String listingTitle, String buyerSub, String sellerSub,
        BigDecimal amount, BigDecimal askingPrice, OfferStatus status, RejectionReason rejectionReason,
        Instant expiresAt, String orderId, Instant createdAt) {
    public static OfferView from(Offer offer) {
        return new OfferView(offer.getId(), offer.getListingId(), offer.getListingTitle(), offer.getBuyerSub(),
                offer.getSellerSub(), offer.getAmount(), offer.getAskingPrice(), offer.getStatus(),
                offer.getRejectionReason(), offer.getExpiresAt(), offer.getOrderId(), offer.getCreatedAt());
    }
}
