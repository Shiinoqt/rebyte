package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus; import it.rebyte.commons.error.DomainException;
public class SelfFavouriteException extends DomainException{public SelfFavouriteException(){super(HttpStatus.UNPROCESSABLE_ENTITY,"self-favourite","A seller cannot favourite their own listing");}}
