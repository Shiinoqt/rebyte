package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OfferAcceptConflictException extends DomainException {
    public OfferAcceptConflictException(String offerId) {
        super(HttpStatus.CONFLICT, "offer-accept-conflict", "Listing changed while accepting offer: " + offerId);
    }
}
