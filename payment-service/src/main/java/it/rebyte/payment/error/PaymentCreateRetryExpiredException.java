package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class PaymentCreateRetryExpiredException extends DomainException {

    public PaymentCreateRetryExpiredException(String orderId) {
        super(HttpStatus.CONFLICT, "payment-create-retry-expired",
                "Payment creation requires reconciliation for order: " + orderId);
    }
}
