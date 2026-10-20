package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

/** V.Offer.noSelfOffer. */
public class SelfOfferException extends DomainException {
    public SelfOfferException() {
        super(HttpStatus.FORBIDDEN, "self-offer-forbidden", "A buyer cannot offer on their own listing");
    }
}
