package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** The order is durably cancelled before the unavailable payment create is reported. */
public class PaymentUnavailableException extends DomainException {

    public PaymentUnavailableException(String orderId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "payment-unavailable",
                "Payment could not be created for order " + orderId + "; the order has been cancelled");
        initCause(cause);
    }
}
