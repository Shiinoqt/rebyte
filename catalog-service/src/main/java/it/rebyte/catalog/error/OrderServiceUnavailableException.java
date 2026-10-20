package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class OrderServiceUnavailableException extends DomainException {
    public OrderServiceUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "order-service-unavailable", "Order service is temporarily unavailable");
        initCause(cause);
    }
}
