package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class DuplicateVersionNumberException extends DomainException {
    public DuplicateVersionNumberException(String number) {
        super(HttpStatus.CONFLICT, "duplicate-version-number", "Version number already exists: " + number);
    }
}
