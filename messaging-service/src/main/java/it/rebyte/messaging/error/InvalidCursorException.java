package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidCursorException extends DomainException {
    public InvalidCursorException() {
        super(HttpStatus.BAD_REQUEST, "invalid-cursor", "Invalid page cursor or size");
    }
}
