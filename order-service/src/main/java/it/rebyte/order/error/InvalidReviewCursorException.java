package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidReviewCursorException extends DomainException {
    public InvalidReviewCursorException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-review-cursor", "Invalid review page cursor or size");
    }
}
