package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidUnsubscribeTokenException extends DomainException {
    public InvalidUnsubscribeTokenException() {
        super(HttpStatus.BAD_REQUEST, "invalid-unsubscribe-token", "Invalid unsubscribe token");
    }
}
