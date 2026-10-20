package it.rebyte.order.messaging;

import java.time.Instant;
import java.util.UUID;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.ListingType;

/** Fulfilment display data includes the listing title, carrier and tracking code; amounts, prices and addresses are never included. */
public record OrderFulfilmentEvent(String eventId, String orderId, String listingId, ListingType listingType,
        String buyerSub, String sellerSub, String listingTitle, String carrier, String trackingCode, Instant occurredAt) {
    public static OrderFulfilmentEvent from(Order order) {
        return new OrderFulfilmentEvent(UUID.randomUUID().toString(), order.getId(), order.getListingId(),
                order.getListingType(), order.getBuyerSub(), order.getSellerSub(), order.getListingTitle(),
                order.getCarrier() == null ? null : order.getCarrier().name(), order.getTrackingCode(), Instant.now());
    }
}
