package it.rebyte.user.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Local projection of {@code review.created} events (D-47, D-48; USER-04).
 * Keyed by the review's own id, so at-least-once delivery is a no-op by
 * construction with no separate dedup table — plan 01-04 adds the listener
 * that fills this table; this plan only creates it and reads it (via {@code
 * SellerReviewRepository}'s {@code AVG}/{@code COUNT} query, never a stored
 * aggregate — D-48).
 */
@Entity
@Table(name = "seller_review")
public class SellerReview {

    @Id
    @Column(name = "review_id", length = 64, nullable = false, updatable = false)
    private String reviewId;

    @Column(name = "seller_sub", length = 64, nullable = false, updatable = false)
    private String sellerSub;

    // byte, not short: Hibernate 6's default SQL-type inference maps
    // byte/Byte to TINYINT and short/Short to SMALLINT (observed as a
    // SchemaManagementException under ddl-auto=validate against the
    // migration's TINYINT column) — byte also comfortably fits a 1-5
    // rating scale.
    @Column(name = "rating", nullable = false, updatable = false)
    private byte rating;

    @Column(name = "instant", nullable = false, updatable = false)
    private Instant instant;

    protected SellerReview() {
        // required by JPA
    }

    public SellerReview(String reviewId, String sellerSub, byte rating, Instant instant) {
        this.reviewId = reviewId;
        this.sellerSub = sellerSub;
        this.rating = rating;
        this.instant = instant;
    }

    public String getReviewId() {
        return reviewId;
    }

    public String getSellerSub() {
        return sellerSub;
    }

    public byte getRating() {
        return rating;
    }

    public Instant getInstant() {
        return instant;
    }
}
