package it.rebyte.order.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

/** V.Offer.singlePendingPerBuyer. */
public class DuplicatePendingOfferException extends DomainException {
    public DuplicatePendingOfferException() {
        super(HttpStatus.CONFLICT, "duplicate-pending-offer", "Buyer already has a pending offer on this listing");
    }
}
