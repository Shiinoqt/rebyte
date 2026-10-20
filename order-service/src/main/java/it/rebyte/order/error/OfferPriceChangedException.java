package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OfferPriceChangedException extends DomainException {
    public OfferPriceChangedException(String offerId) {
        super(HttpStatus.CONFLICT, "offer-price-changed", "Listing price changed for offer: " + offerId);
    }
}
