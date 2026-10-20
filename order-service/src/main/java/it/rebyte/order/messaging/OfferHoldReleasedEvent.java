package it.rebyte.order.messaging;

import java.time.Instant;
import java.util.UUID;

import it.rebyte.order.domain.Offer;

public record OfferHoldReleasedEvent(String eventId, String offerId, String listingId, String listingType,
        String reservationId, Instant occurredAt) {
    public static OfferHoldReleasedEvent of(Offer offer, String holdReservationId) {
        return new OfferHoldReleasedEvent(UUID.randomUUID().toString(), offer.getId(), offer.getListingId(),
                "HARDWARE", holdReservationId, Instant.now());
    }
}
