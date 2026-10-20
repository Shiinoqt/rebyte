package it.rebyte.user.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Per-pair transition counter behind the {@code relationshipSequence} of the
 * follow events. Every method must run inside the caller's transaction: the
 * row lock taken by {@link #lock} is what serializes concurrent transitions
 * of one pair, and it is held until that transaction ends.
 */
@Repository
public class FollowSequenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public FollowSequenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Creates the pair row if absent and locks it. The no-op update (rather
     * than INSERT IGNORE) takes an exclusive lock even when the row exists,
     * so two first transitions of the same pair queue instead of deadlocking.
     */
    public void lock(String followerSub, String followedSub) {
        jdbcTemplate.update(
                "INSERT INTO follow_transition_sequence (follower_sub, followed_sub) VALUES (?, ?) "
                        + "ON DUPLICATE KEY UPDATE follower_sub = follower_sub",
                followerSub, followedSub);
        jdbcTemplate.queryForObject(
                "SELECT transition_sequence FROM follow_transition_sequence "
                        + "WHERE follower_sub = ? AND followed_sub = ? FOR UPDATE",
                Long.class, followerSub, followedSub);
    }

    /** Increments the locked pair counter and returns the new value. */
    public long next(String followerSub, String followedSub) {
        jdbcTemplate.update(
                "UPDATE follow_transition_sequence SET transition_sequence = transition_sequence + 1 "
                        + "WHERE follower_sub = ? AND followed_sub = ?",
                followerSub, followedSub);
        Long value = jdbcTemplate.queryForObject(
                "SELECT transition_sequence FROM follow_transition_sequence "
                        + "WHERE follower_sub = ? AND followed_sub = ?",
                Long.class, followerSub, followedSub);
        return value;
    }
}
