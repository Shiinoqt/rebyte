package it.rebyte.user.domain;

import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * {@code Follow}'s external composite identifier (D-41): the pair {@code
 * (followerSub, followedSub)}, both Cognito {@code sub} values (D-01,
 * AMENDED — {@code String}, never {@link java.util.UUID}). Embedded as
 * {@code @EmbeddedId} on {@link Follow} rather than a surrogate, so ch.
 * 10.6's external identifier is visible directly in the entity and in the
 * table's primary key, and so that the pair's own uniqueness (half of
 * {@code [V.Follow.noSelfFollow]}) is enforced by the primary key itself
 * with no separate unique index.
 *
 * <p>{@code equals}/{@code hashCode} over both fields are required for a
 * JPA embeddable id to behave correctly as a map/set key and in Hibernate's
 * identity checks.
 */
@Embeddable
public class FollowId implements Serializable {

    @Column(name = "follower_sub", length = 64, nullable = false, updatable = false)
    private String followerSub;

    @Column(name = "followed_sub", length = 64, nullable = false, updatable = false)
    private String followedSub;

    protected FollowId() {
        // required by JPA
    }

    public FollowId(String followerSub, String followedSub) {
        this.followerSub = followerSub;
        this.followedSub = followedSub;
    }

    public String getFollowerSub() {
        return followerSub;
    }

    public String getFollowedSub() {
        return followedSub;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof FollowId other)) {
            return false;
        }
        return Objects.equals(followerSub, other.followerSub) && Objects.equals(followedSub, other.followedSub);
    }

    @Override
    public int hashCode() {
        return Objects.hash(followerSub, followedSub);
    }
}
