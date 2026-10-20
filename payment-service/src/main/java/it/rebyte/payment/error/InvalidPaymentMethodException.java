package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;
import it.rebyte.payment.domain.PaymentMethod;

/**
 * Defensive guard: order-service already refuses {@code BANK_TRANSFER} at
 * checkout (D-20), so this should never actually be sent here — but
 * payment-service does not trust the caller's method field blindly either.
 */
public class InvalidPaymentMethodException extends DomainException {

    public InvalidPaymentMethodException(PaymentMethod method) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-payment-method", "Unsupported payment method: " + method);
    }
}
