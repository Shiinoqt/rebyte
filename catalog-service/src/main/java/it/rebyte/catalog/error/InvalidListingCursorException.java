package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidListingCursorException extends DomainException {

    public InvalidListingCursorException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-listing-cursor",
                "The cursor is malformed or the page size is outside 1 to 50");
    }
}
