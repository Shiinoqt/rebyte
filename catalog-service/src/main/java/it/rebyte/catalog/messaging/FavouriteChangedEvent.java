package it.rebyte.catalog.messaging;

import java.time.Instant;

/** The eventId matches the outbox eventId; occurredAt is the persisted add or removal instant in milliseconds, listingTitle is notification display data, and relationshipSequence orders transitions for the pair. */
public record FavouriteChangedEvent(String eventId, String userSub, String listingId, String sellerSub,
        String listingTitle, Instant occurredAt, Long relationshipSequence) {}
