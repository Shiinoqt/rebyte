package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ProblemNotFoundException extends DomainException {
    public ProblemNotFoundException() {
        super(HttpStatus.NOT_FOUND, "problem-not-found", "No problem was reported for this order");
    }
}
