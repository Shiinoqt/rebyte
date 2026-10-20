package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * Catalog reserve returned an unknown outcome (timeout, both retry attempts
 * failed) while verifying an offer's hold during checkout; nothing was
 * written (03.1-CONTEXT.md D-06 pattern applied to checkout verification).
 */
public class OfferHoldUnverifiedException extends DomainException {

    public OfferHoldUnverifiedException(String offerId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "offer-hold-unverified",
                "Could not verify catalog hold for offer: " + offerId);
        initCause(cause);
    }
}
