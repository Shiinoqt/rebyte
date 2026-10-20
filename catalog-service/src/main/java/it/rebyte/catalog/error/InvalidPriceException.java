package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** A price must be positive and representable at the configured currency scale. */
public class InvalidPriceException extends DomainException {

    public InvalidPriceException(int scale) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-price",
                "Price must be positive and use at most " + scale + " decimal places");
    }
}
