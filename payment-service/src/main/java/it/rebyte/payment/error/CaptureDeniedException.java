package it.rebyte.payment.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * order-service denied, timed out, or was unreachable for the capture-
 * authorization grant (Task 2 action: "Denial, timeout or unavailable
 * order-service makes zero PayPal capture calls"). No PayPal call is ever
 * made when this is thrown — see {@link it.rebyte.payment.service.PaymentService#capture}.
 */
public class CaptureDeniedException extends DomainException {

    public CaptureDeniedException(String orderId) {
        super(HttpStatus.CONFLICT, "capture-denied",
                "Capture authorization was denied or unavailable for order: " + orderId);
    }
}
