package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** The requested {@code listingId} does not match the offer's own listing (03.1-CONTEXT.md D-12). */
public class OfferListingMismatchException extends DomainException {

    public OfferListingMismatchException(String offerId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "offer-listing-mismatch",
                "Offer does not belong to the requested listing: " + offerId);
    }
}
