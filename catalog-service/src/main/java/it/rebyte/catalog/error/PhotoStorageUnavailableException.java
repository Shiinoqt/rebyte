package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class PhotoStorageUnavailableException extends DomainException {
    public PhotoStorageUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "photo-storage-unavailable", "Photo storage is temporarily unavailable");
    }
}
