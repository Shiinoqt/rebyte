package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class InvalidVersionNumberException extends DomainException {
    public InvalidVersionNumberException(String number) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-version-number", "Invalid version number: " + number);
    }
}
