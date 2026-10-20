package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidOrderCursorException extends DomainException {

    public InvalidOrderCursorException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-order-cursor", "Invalid order page cursor or size");
    }
}
