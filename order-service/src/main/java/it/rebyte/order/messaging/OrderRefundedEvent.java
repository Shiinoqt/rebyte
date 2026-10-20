package it.rebyte.order.messaging;

import java.time.Instant;
import java.util.UUID;
import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;

/** Catalog releases only refunds from PAID: after shipping, the item has left the seller. Listing title is display data; amounts, prices and addresses are never included. */
public record OrderRefundedEvent(String eventId, String orderId, String listingId, ListingType listingType,
        String buyerSub, String sellerSub, String listingTitle, String reservationId, CancellationReason cancellationReason,
        OrderStatus refundedFromStatus, Instant occurredAt) {
    public static OrderRefundedEvent of(Order order, OrderStatus refundedFrom) {
        return new OrderRefundedEvent(UUID.randomUUID().toString(), order.getId(), order.getListingId(),
                order.getListingType(), order.getBuyerSub(), order.getSellerSub(), order.getListingTitle(),
                refundedFrom == OrderStatus.PAID ? order.getReservationId() : null,
                order.getCancellationReason(), refundedFrom, Instant.now());
    }
}
