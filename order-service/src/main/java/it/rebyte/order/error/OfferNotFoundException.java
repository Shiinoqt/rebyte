package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OfferNotFoundException extends DomainException {
    public OfferNotFoundException(String offerId) {
        super(HttpStatus.NOT_FOUND, "offer-not-found", "Offer not found: " + offerId);
    }
}
