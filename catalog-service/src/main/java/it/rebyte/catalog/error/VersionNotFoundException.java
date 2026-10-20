package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class VersionNotFoundException extends DomainException {
    public VersionNotFoundException(String listingId, String versionNumber) {
        super(HttpStatus.NOT_FOUND, "version-not-found", "Version " + versionNumber + " was not found for listing " + listingId);
    }
}
