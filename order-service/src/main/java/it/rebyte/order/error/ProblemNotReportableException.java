package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ProblemNotReportableException extends DomainException {
    public ProblemNotReportableException() {
        super(HttpStatus.CONFLICT, "problem-not-reportable", "A problem cannot be reported for this order now");
    }
}
