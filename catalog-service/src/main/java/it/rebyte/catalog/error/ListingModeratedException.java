package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class ListingModeratedException extends DomainException {
    public ListingModeratedException() {
        super(HttpStatus.FORBIDDEN, "listing-moderated", "This listing is unavailable due to moderation");
    }
}
