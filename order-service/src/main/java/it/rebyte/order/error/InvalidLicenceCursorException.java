package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidLicenceCursorException extends DomainException {

    public InvalidLicenceCursorException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-licence-cursor", "Invalid licence page cursor or size");
    }
}
