package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidLicenceKeyPatternException extends DomainException {
    public InvalidLicenceKeyPatternException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-licence-key-pattern",
                "Licence key pattern must use A-Z, 0-9, hyphen and #, contain at least 16 #, and be at most 64 characters");
    }
}
