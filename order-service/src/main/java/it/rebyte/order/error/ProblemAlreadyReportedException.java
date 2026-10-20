package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ProblemAlreadyReportedException extends DomainException {
    public ProblemAlreadyReportedException() {
        super(HttpStatus.CONFLICT, "problem-already-reported", "A problem was already reported for this order");
    }
}
