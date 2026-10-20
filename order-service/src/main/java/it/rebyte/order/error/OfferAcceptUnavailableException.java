package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OfferAcceptUnavailableException extends DomainException {
    public OfferAcceptUnavailableException(String offerId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "offer-accept-unavailable",
                "Could not confirm catalog reservation for offer: " + offerId);
        initCause(cause);
    }
}
