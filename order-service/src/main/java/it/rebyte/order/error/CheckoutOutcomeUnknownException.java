package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * Task 2 (03-04-PLAN.md): a catalog reserve retried once with the same
 * caller-supplied {@code reservationId} (D-17) still failed to resolve, or
 * the payment-create call failed outright. The durable cancellation outbox
 * that would let order-service safely cancel and release the listing here
 * ships in a later plan (03-11) — until then the order and its guard are
 * left in place rather than compensated, and checkout success is never
 * reported for this request.
 */
public class CheckoutOutcomeUnknownException extends DomainException {

    public CheckoutOutcomeUnknownException(String orderId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "checkout-outcome-unknown",
                "Could not confirm the checkout outcome for order " + orderId);
        initCause(cause);
    }
}
