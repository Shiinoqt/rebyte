package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** D-35: amounts are BigDecimal end to end; anything above two decimal digits is rejected, never rounded. */
public class InvalidAmountScaleException extends DomainException {

    public InvalidAmountScaleException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-amount-scale", "Amounts must use at most two decimal digits");
    }
}
