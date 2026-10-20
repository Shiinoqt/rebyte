package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidDeleteFilterException extends DomainException {
    public InvalidDeleteFilterException() {
        super(HttpStatus.BAD_REQUEST, "invalid-delete-filter", "Bulk deletion requires read=true");
    }
}
