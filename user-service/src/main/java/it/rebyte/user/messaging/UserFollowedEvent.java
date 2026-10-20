package it.rebyte.user.messaging;

import java.time.Instant;

import it.rebyte.commons.messaging.RebyteExchanges;

/**
 * Payload of the {@code user.followed} event (SOCL-01, D-21, D-25). Carries
 * identifiers and an instant only — no display data. A consumer wanting a
 * username or avatar calls {@code GET /internal/users/{sub}} instead.
 *
 * <p>Deliberately NOT shared via {@code it.rebyte.commons} (D-22): catalog
 * and messaging must hand-copy this exact shape to consume it. The shape is
 * immutable by design specifically so that copy stays cheap to keep in
 * sync.
 *
 * <p>{@code relationshipSequence} is the per-pair transition counter that
 * orders follow and unfollow events of one pair even when their {@code
 * occurredAt} values coincide. It is null in payloads from producers that
 * predate it, so consumers must treat it as optional.
 */
public record UserFollowedEvent(
        String eventId, String type, String followerSub, String followedSub, Instant occurredAt,
        Long relationshipSequence) {

    public UserFollowedEvent(String eventId, String followerSub, String followedSub, Instant occurredAt) {
        this(eventId, RebyteExchanges.USER_FOLLOWED, followerSub, followedSub, occurredAt, null);
    }

    public UserFollowedEvent(String eventId, String type, String followerSub, String followedSub, Instant occurredAt) {
        this(eventId, type, followerSub, followedSub, occurredAt, null);
    }
}
