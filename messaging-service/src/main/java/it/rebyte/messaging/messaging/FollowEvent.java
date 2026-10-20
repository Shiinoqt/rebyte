package it.rebyte.messaging.messaging;

import java.time.Instant;

public record FollowEvent(String eventId, String type, String followerSub, String followedSub,
        Instant occurredAt, Long relationshipSequence) {
}
