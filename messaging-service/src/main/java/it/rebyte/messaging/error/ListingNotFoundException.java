package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ListingNotFoundException extends DomainException {
    public ListingNotFoundException() {
        super(HttpStatus.NOT_FOUND, "listing-not-found", "Listing not found");
    }
}
