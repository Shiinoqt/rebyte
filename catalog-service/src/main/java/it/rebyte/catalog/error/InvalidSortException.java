package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidSortException extends DomainException {
    public InvalidSortException(String detail) {
        super(HttpStatus.BAD_REQUEST, "invalid-sort", detail);
    }
}
