package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OrderRefundPendingException extends DomainException {
    public OrderRefundPendingException() {
        super(HttpStatus.CONFLICT, "order-refund-pending", "Order refund is pending");
    }
}
