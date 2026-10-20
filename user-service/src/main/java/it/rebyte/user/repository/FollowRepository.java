package it.rebyte.user.repository;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.rebyte.user.domain.Follow;
import it.rebyte.user.domain.FollowId;

/**
 * Follow-graph persistence (SOCL-01, SOCL-02, SOCL-05).
 *
 * <p>{@link #findFollowerSubsPage} is D-40's keyset-paginated followers
 * read: ordered by {@code follower_sub} ascending with a strict {@code >}
 * comparison against the cursor, so a row whose sub equals the cursor
 * belongs to the earlier page and is never returned a second time. An
 * empty-string cursor (used for the first page — no real Cognito {@code
 * sub} is ever empty) selects every row, since every non-empty string
 * sorts after {@code ""} under this schema's collation.
 *
 * <p>Public profiles expose counts in both directions without exposing
 * the identities returned by the internal followers page.
 */
public interface FollowRepository extends JpaRepository<Follow, FollowId> {

    @Query("SELECT COUNT(f) FROM Follow f WHERE f.id.followedSub = :sub")
    long countFollowers(@Param("sub") String sub);

    @Query("SELECT COUNT(f) FROM Follow f WHERE f.id.followerSub = :sub")
    long countFollowing(@Param("sub") String sub);

    @Query("SELECT f.id.followerSub FROM Follow f "
            + "WHERE f.id.followedSub = :followedSub AND f.id.followerSub > :cursor "
            + "ORDER BY f.id.followerSub ASC")
    List<String> findFollowerSubsPage(
            @Param("followedSub") String followedSub, @Param("cursor") String cursor, Pageable pageable);
}
