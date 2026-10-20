package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.util.Set;
import it.rebyte.catalog.domain.ListingType;
import it.rebyte.catalog.domain.PaymentMethod;

/**
 * Uncached checkout snapshot (D-09, D-13, D-20). {@code shippingCost} is
 * {@code 0.00} for software (no {@code HardwareListing} part). {@code
 * version} is the listing's Mongo {@code @Version} value at read time — the
 * order-service caller must echo it back as {@code expectedVersion} on
 * reserve, so an edit between this read and the reserve call fences the
 * reserve instead of charging a stale price. The reservation response is
 * still the final availability guard.
 */
public record ListingAvailability(String id, String sellerSub, String listingTitle, BigDecimal price,
        BigDecimal shippingCost, ListingType type, Set<PaymentMethod> paymentMethods, boolean available,
        long version, String licenceKeyPattern) {
}
