package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class DeveloperProfileRequiredException extends DomainException {
    public DeveloperProfileRequiredException() {
        super(HttpStatus.FORBIDDEN, "developer-profile-required", "An active developer profile is required");
    }
}
