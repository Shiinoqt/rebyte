package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ReviewNotFoundException extends DomainException {
    public ReviewNotFoundException(String reviewId) {
        super(HttpStatus.NOT_FOUND, "review-not-found", "Review not found: " + reviewId);
    }
}
