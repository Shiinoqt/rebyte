package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class PhotoNotUploadedException extends DomainException {
    public PhotoNotUploadedException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "photo-not-uploaded", "Upload a valid image before confirming it");
    }
}
