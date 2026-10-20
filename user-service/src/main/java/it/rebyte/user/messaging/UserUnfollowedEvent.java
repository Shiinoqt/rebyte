package it.rebyte.user.messaging;

import java.time.Instant;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Payload of the {@code user.unfollowed} event (SOCL-02, D-21, D-25). See
 * {@link UserFollowedEvent} for the identifier-only shape rationale and the
 * optional {@code relationshipSequence}.
 */
public record UserUnfollowedEvent(
        String eventId, String type, String followerSub, String followedSub, Instant occurredAt,
        Long relationshipSequence) {

    public UserUnfollowedEvent(String eventId, String followerSub, String followedSub, Instant occurredAt) {
        this(eventId, RebyteExchanges.USER_UNFOLLOWED, followerSub, followedSub, occurredAt, null);
    }

    public UserUnfollowedEvent(String eventId, String type, String followerSub, String followedSub, Instant occurredAt) {
        this(eventId, type, followerSub, followedSub, occurredAt, null);
    }
}
