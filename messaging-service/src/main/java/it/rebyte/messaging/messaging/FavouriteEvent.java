package it.rebyte.messaging.messaging;

import java.time.Instant;

public record FavouriteEvent(String eventId, String userSub, String listingId, String sellerSub, String listingTitle,
        Instant occurredAt, Long relationshipSequence) {
}
