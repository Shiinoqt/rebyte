package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;
import it.rebyte.order.domain.PaymentMethod;

/**
 * D-20: {@code bankTransfer} is always refused, and any other method must
 * be present in the listing's own {@code paymentMethods}.
 */
public class InvalidPaymentMethodException extends DomainException {

    public InvalidPaymentMethodException(PaymentMethod method) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "payment-method-not-allowed",
                "Payment method not allowed for this listing: " + method);
    }
}
