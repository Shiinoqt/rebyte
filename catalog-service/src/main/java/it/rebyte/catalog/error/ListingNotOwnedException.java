package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * Declared now (Task 1) even though its first real caller is Task 2's photo
 * confirm step — mirrors user-service's {@code AvatarKeyOwnershipException}
 * (T-06-06): a photo key not under the caller's own listing prefix is
 * refused, never silently accepted.
 */
public class ListingNotOwnedException extends DomainException {

    public ListingNotOwnedException(String listingId) {
        super(HttpStatus.FORBIDDEN, "listing-not-owned", "Listing " + listingId + " is not owned by the caller");
    }
}
