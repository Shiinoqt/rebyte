package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** An order id that does not exist — {@code GET}/internal capture-authorization routes. */
public class OrderNotFoundException extends DomainException {

    public OrderNotFoundException(String orderId) {
        super(HttpStatus.NOT_FOUND, "order-not-found", "Order not found: " + orderId);
    }
}
