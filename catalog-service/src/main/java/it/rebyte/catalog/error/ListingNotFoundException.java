package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A route naming a {@code listingId} that does not exist. Declared now
 * (Task 1) even though its first real callers are Task 2's photo-confirm
 * step and Task 3's internal read/reserve contract — both need "nothing
 * there" distinguished from {@link ListingNotOwnedException}'s "something
 * there, but not yours".
 */
public class ListingNotFoundException extends DomainException {

    public ListingNotFoundException(String listingId) {
        super(HttpStatus.NOT_FOUND, "listing-not-found", "Listing not found: " + listingId);
    }
}
