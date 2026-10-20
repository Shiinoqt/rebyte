package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * D-16: the loser of the MySQL guard race (duplicate {@code
 * active_listing_guard}), or a catalog reserve outcome of {@code
 * ALREADY_TAKEN}/{@code NOT_FOUND}/{@code NOT_RESERVABLE}. Same 409 code
 * either way — both mean "this listing cannot be bought by this request
 * any more".
 */
public class ListingUnavailableException extends DomainException {

    public ListingUnavailableException(String listingId) {
        super(HttpStatus.CONFLICT, "listing-unavailable", "Listing is not available for purchase: " + listingId);
    }
}
