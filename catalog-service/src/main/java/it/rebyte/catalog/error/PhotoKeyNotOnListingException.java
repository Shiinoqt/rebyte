package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** Photos enter a listing only through upload-policy and confirm, which verify the uploaded object. */
public class PhotoKeyNotOnListingException extends DomainException {

    public PhotoKeyNotOnListingException(int rejectedCount) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "photo-key-not-on-listing",
                "Photo list contains " + rejectedCount + " rejected entries");
    }
}
