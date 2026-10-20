package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class RefundUnavailableException extends DomainException {
    public RefundUnavailableException(String orderId, Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "refund-unavailable", "Refund unavailable for order " + orderId);
        initCause(cause);
    }
}
