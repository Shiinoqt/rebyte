package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OfferAcceptInProgressException extends DomainException {
    public OfferAcceptInProgressException(String offerId) {
        super(HttpStatus.CONFLICT, "offer-accept-in-progress", "Offer acceptance is already in progress: " + offerId);
    }
}
