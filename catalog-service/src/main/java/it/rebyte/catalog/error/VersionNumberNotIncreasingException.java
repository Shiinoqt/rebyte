package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class VersionNumberNotIncreasingException extends DomainException {
    public VersionNumberNotIncreasingException(String number) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "version-number-not-increasing", "Version number must increase: " + number);
    }
}
