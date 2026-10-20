package it.rebyte.order.error;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * D-09: {@code expectedTotal} did not match the fresh
 * {@code price + shippingCost} read from catalog. {@link #getCurrentTotal()}
 * is surfaced as a {@code currentTotal} extension property by
 * {@link it.rebyte.order.config.OrderApiExceptionHandler}.
 */
public class PriceMismatchException extends DomainException {

    private final BigDecimal currentTotal;

    public PriceMismatchException(BigDecimal currentTotal) {
        super(HttpStatus.CONFLICT, "price-mismatch",
                "expectedTotal does not match the current listing price and shipping cost");
        this.currentTotal = currentTotal;
    }

    public BigDecimal getCurrentTotal() {
        return currentTotal;
    }
}
