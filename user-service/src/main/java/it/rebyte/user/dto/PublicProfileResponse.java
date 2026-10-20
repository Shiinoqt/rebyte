package it.rebyte.user.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /users/{sub}} (USER-04, D-38, D-50). Ratings come from the
 * local {@code review.created} projection and completed sales from order
 * delivery/refund events. {@code ratingAvg} is {@code null} and {@code
 * ratingCount} is {@code 0} when the seller has no reviews.
 * {@code activeListings} stays empty because clients read seller listings
 * from catalog directly.
 *
 * <p>Follower and following counts are public; the identities behind those
 * counts are not. {@code followedByMe} describes only the authenticated
 * caller's relationship and is false for anonymous callers and the owner.
 *
 * <p>{@code avatar} is {@code null} for a user with none (D-38) — the
 * client renders initials. {@code registration} is {@code null} for a
 * never-provisioned {@code sub}, see {@link
 * it.rebyte.user.service.UserProfileService#getPublicProfile(String, String)}.
 */
public record PublicProfileResponse(
        String sub,
        String username,
        String avatar,
        Instant registration,
        String location,
        String bio,
        boolean isDeveloper,
        Double ratingAvg,
        long ratingCount,
        long completedSales,
        List<String> activeListings,
        long followerCount,
        long followingCount,
        boolean followedByMe) {
}
