package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class AttributeFilterNotAllowedException extends DomainException {
    public AttributeFilterNotAllowedException(String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "attribute-filter-not-allowed", detail);
    }
}
