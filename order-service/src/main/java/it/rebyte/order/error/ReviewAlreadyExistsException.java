package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ReviewAlreadyExistsException extends DomainException {
    public ReviewAlreadyExistsException() {
        super(HttpStatus.CONFLICT, "review-already-exists", "This order has already been reviewed");
    }
}
