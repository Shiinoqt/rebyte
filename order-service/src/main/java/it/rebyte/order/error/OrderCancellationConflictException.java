package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** A capture grant or terminal transition won before cancellation. */
public class OrderCancellationConflictException extends DomainException {

    public OrderCancellationConflictException(String orderId) {
        super(HttpStatus.CONFLICT, "order-cancellation-conflict",
                "Order " + orderId + " cannot be cancelled in its current state");
    }
}
