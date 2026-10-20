package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** V.Order.noSelfPurchase / T-03-07: JWT sub compared against a fresh sellerSub before any order insert. */
public class SelfPurchaseException extends DomainException {

    public SelfPurchaseException() {
        super(HttpStatus.FORBIDDEN, "self-purchase-forbidden", "A buyer cannot purchase their own listing");
    }
}
