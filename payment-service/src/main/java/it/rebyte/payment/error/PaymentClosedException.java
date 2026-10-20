package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The payment was already closed (D-02 amendment: "a payment close may
 * commit CLOSED only while OPEN") before this capture request could claim
 * it — capture is refused rather than reopening a closed payment.
 */
public class PaymentClosedException extends DomainException {

    public PaymentClosedException(String orderId) {
        super(HttpStatus.CONFLICT, "payment-closed", "Payment already closed for order: " + orderId);
    }
}
