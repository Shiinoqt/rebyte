package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class InvalidOfferAmountException extends DomainException {
    public InvalidOfferAmountException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-offer-amount",
                "Offer amount must be positive and strictly below the asking price");
    }
}
