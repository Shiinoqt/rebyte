package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** No Payment row exists for this order id — capture/close cannot proceed. */
public class PaymentNotFoundException extends DomainException {

    public PaymentNotFoundException(String orderId) {
        super(HttpStatus.NOT_FOUND, "payment-not-found", "Payment not found for order: " + orderId);
    }
}
