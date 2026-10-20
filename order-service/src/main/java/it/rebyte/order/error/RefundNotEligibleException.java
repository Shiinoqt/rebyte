package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class RefundNotEligibleException extends DomainException {
    public RefundNotEligibleException() {
        super(HttpStatus.CONFLICT, "refund-not-eligible", "Payment is not eligible for refund");
    }
}
