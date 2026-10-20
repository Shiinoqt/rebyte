package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidProblemFilterException extends DomainException {
    public InvalidProblemFilterException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-problem-filter", "Problem queue filter is invalid");
    }
}
