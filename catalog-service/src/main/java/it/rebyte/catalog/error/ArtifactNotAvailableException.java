package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class ArtifactNotAvailableException extends DomainException {
    public ArtifactNotAvailableException() {
        super(HttpStatus.CONFLICT, "artifact-not-available", "This version does not have an available artifact");
    }
}
