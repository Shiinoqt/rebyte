package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class LicenceRequiredException extends DomainException {
    public LicenceRequiredException() {
        super(HttpStatus.FORBIDDEN, "licence-required", "An active licence is required to download this artifact");
    }
}
