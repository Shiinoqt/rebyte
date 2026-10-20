package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus; import it.rebyte.commons.error.DomainException;
public class FavouriteNotFoundException extends DomainException{public FavouriteNotFoundException(String id){super(HttpStatus.NOT_FOUND,"favourite-not-found","Favourite not found: "+id);}}
