package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

/** V.Offer.hardwareOnly. */
public class OfferHardwareOnlyException extends DomainException {
    public OfferHardwareOnlyException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "offer-hardware-only", "Offers are for hardware listings only");
    }
}
