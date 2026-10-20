package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ProblemNotOpenException extends DomainException {
    public ProblemNotOpenException() {
        super(HttpStatus.CONFLICT, "problem-not-open", "Only an open problem can be withdrawn");
    }
}
