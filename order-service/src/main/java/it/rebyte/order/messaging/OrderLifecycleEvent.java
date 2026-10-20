package it.rebyte.order.messaging;

import java.time.Instant;
import java.util.UUID;

import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;

/** Order lifecycle data for consumers: identifiers, listing title for display, reservation id and cancellation reason. Amounts, prices and addresses are never included. */
public record OrderLifecycleEvent(String eventId, String orderId, String listingId, ListingType listingType,
        String buyerSub, String sellerSub, String listingTitle, String reservationId, CancellationReason cancellationReason,
        Instant occurredAt) {

    public static OrderLifecycleEvent from(Order order) {
        return new OrderLifecycleEvent(UUID.randomUUID().toString(), order.getId(), order.getListingId(),
                order.getListingType(), order.getBuyerSub(), order.getSellerSub(), order.getListingTitle(), order.getReservationId(),
                order.getCancellationReason(), Instant.now());
    }
}
