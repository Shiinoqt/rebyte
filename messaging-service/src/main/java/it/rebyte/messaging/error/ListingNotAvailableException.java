package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ListingNotAvailableException extends DomainException {
    public ListingNotAvailableException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "listing-not-available", "Listing is not available");
    }
}
