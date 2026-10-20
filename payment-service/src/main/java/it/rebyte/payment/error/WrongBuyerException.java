package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The authenticated JWT subject does not match the immutable {@code
 * buyerSub} captured at {@code POST /internal/payments} time (D-02,
 * T-03-10). Never compares against any client-suppliable field.
 */
public class WrongBuyerException extends DomainException {

    public WrongBuyerException() {
        super(HttpStatus.FORBIDDEN, "wrong-buyer", "Only the order's buyer may capture this payment");
    }
}
