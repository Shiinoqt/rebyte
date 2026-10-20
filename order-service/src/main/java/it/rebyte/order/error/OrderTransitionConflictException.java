package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OrderTransitionConflictException extends DomainException {
    public OrderTransitionConflictException() {
        super(HttpStatus.CONFLICT, "order-transition-conflict", "Order cannot make this transition");
    }
}
