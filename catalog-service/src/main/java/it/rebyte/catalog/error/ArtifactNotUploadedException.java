package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class ArtifactNotUploadedException extends DomainException {
    public ArtifactNotUploadedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "artifact-not-uploaded", "Upload a valid artifact for this version before confirming it");
    }
}
