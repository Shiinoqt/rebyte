package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class ArtifactAlreadyAttachedException extends DomainException {
    public ArtifactAlreadyAttachedException() {
        super(HttpStatus.CONFLICT, "artifact-already-attached", "An artifact is already attached to this version");
    }
}
