package it.rebyte.order.dto;

import java.time.Instant;

import it.rebyte.order.domain.Review;

public record ReviewView(String id, String listingId, String listingTitle, String authorUsername,
        String sellerSub, int rating, String comment, Instant createdAt) {

    public static ReviewView from(Review review, String authorUsername) {
        return new ReviewView(review.getId(), review.getListingId(), review.getListingTitle(), authorUsername,
                review.getSellerSub(), review.getRating(), review.getComment(),
                review.getCreatedAt());
    }
}
