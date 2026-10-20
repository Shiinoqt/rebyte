package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The payment has no capture this service has itself confirmed COMPLETED
 * via a real, verified webhook (03-10-PLAN.md Task 2, D-27) — includes a
 * payment never captured, one still only optimistically COMPLETED pending
 * webhook confirmation (D-26's "captured, awaiting webhook"), and one
 * already refunded once.
 */
public class RefundNotEligibleException extends DomainException {

    public RefundNotEligibleException(String orderId) {
        super(HttpStatus.CONFLICT, "refund-not-eligible",
                "Payment has no webhook-verified capture eligible for refund: " + orderId);
    }
}
