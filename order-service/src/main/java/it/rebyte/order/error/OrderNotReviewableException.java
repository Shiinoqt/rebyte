package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class OrderNotReviewableException extends DomainException {
    public OrderNotReviewableException() {
        super(HttpStatus.CONFLICT, "order-not-reviewable", "Only a delivered order can be reviewed");
    }
}
