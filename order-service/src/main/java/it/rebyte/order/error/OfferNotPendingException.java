package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OfferNotPendingException extends DomainException {
    public OfferNotPendingException(String offerId) {
        super(HttpStatus.CONFLICT, "offer-not-pending", "Offer is no longer pending: " + offerId);
    }
}
