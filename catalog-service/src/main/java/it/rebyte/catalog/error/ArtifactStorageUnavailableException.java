package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class ArtifactStorageUnavailableException extends DomainException {
    public ArtifactStorageUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "artifact-storage-unavailable", "Artifact storage is temporarily unavailable");
    }
}
