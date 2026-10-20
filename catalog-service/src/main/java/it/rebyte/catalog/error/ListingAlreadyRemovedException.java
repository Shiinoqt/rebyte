package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus; import it.rebyte.commons.error.DomainException;
public class ListingAlreadyRemovedException extends DomainException{public ListingAlreadyRemovedException(String id){super(HttpStatus.CONFLICT,"listing-already-removed","Listing already removed: "+id);}}
