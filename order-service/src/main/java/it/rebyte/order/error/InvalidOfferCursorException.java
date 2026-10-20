package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class InvalidOfferCursorException extends DomainException {
    public InvalidOfferCursorException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-offer-cursor", "Invalid offer page cursor or size");
    }
}
