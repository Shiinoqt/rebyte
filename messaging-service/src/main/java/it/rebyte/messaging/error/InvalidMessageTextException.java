package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidMessageTextException extends DomainException {
    public InvalidMessageTextException() {
        super(HttpStatus.BAD_REQUEST, "invalid-message-text", "Invalid message text");
    }
}
