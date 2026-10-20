package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus; import it.rebyte.commons.error.DomainException;
public class InvalidPriceRangeException extends DomainException{public InvalidPriceRangeException(){super(HttpStatus.UNPROCESSABLE_ENTITY,"invalid-price-range","Minimum price must not exceed maximum price");}}
