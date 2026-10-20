package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OrderActionForbiddenException extends DomainException {
    public OrderActionForbiddenException() {
        super(HttpStatus.FORBIDDEN, "order-action-forbidden", "Only the seller can fulfil this order");
    }
}
