package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;
public class ListingNotEditableException extends DomainException {
    public ListingNotEditableException(String id) { super(HttpStatus.UNPROCESSABLE_ENTITY, "listing-not-editable", "Listing is not active: " + id); }
}
