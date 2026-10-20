package it.rebyte.user.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.rebyte.user.domain.SellerReview;

/**
 * The reputation read (USER-04, D-48). No stored {@code ratingAvg}/{@code
 * ratingCount} columns anywhere — these are {@code AVG}/{@code COUNT}
 * queries over {@code seller_review}, served by the index on {@code
 * seller_sub}, evaluated per request. {@code averageRatingBySellerSub}
 * returns {@code null} when the seller has zero reviews (SQL {@code AVG}
 * over an empty set is {@code NULL}), matching ch. 7.1's stated
 * non-empty-review-set precondition — no separate null-check needed at the
 * call site for that case.
 */
public interface SellerReviewRepository extends JpaRepository<SellerReview, String> {

    @Modifying
    @Query(value = "INSERT INTO seller_review (review_id, seller_sub, rating, instant) "
            + "VALUES (:reviewId, :sellerSub, :rating, :instant) ON DUPLICATE KEY UPDATE review_id = review_id",
            nativeQuery = true)
    int insertIfAbsent(@Param("reviewId") String reviewId, @Param("sellerSub") String sellerSub,
            @Param("rating") byte rating, @Param("instant") Instant instant);

    @Query("SELECT COUNT(r) FROM SellerReview r WHERE r.sellerSub = :sellerSub")
    long countBySellerSub(@Param("sellerSub") String sellerSub);

    @Query(value = "SELECT AVG(CAST(rating AS DOUBLE)) FROM seller_review WHERE seller_sub = :sellerSub",
            nativeQuery = true)
    Double averageRatingBySellerSub(@Param("sellerSub") String sellerSub);
}
