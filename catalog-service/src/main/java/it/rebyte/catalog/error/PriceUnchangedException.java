package it.rebyte.catalog.error;
import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;
public class PriceUnchangedException extends DomainException {
    public PriceUnchangedException() { super(HttpStatus.UNPROCESSABLE_ENTITY, "price-unchanged", "The new price equals the current price"); }
}
