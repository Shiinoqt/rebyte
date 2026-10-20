package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus; import it.rebyte.commons.error.DomainException;
public class ListingNotAvailableException extends DomainException{public ListingNotAvailableException(String id){super(HttpStatus.UNPROCESSABLE_ENTITY,"listing-not-available","Listing is not available: "+id);}}
