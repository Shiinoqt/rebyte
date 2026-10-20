package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The offer is not in a state the caller can check out from: not {@code
 * ACCEPTED}, its hold already expired, or it already has a live order link
 * (03.1-CONTEXT.md D-08, D-10, D-12).
 */
public class OfferNotCheckoutableException extends DomainException {

    public OfferNotCheckoutableException(String offerId) {
        super(HttpStatus.CONFLICT, "offer-not-checkoutable", "Offer is not checkoutable: " + offerId);
    }
}
