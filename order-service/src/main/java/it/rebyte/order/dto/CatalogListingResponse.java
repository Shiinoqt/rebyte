package it.rebyte.order.dto;

import java.math.BigDecimal;
import java.util.Set;

import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.PaymentMethod;

/**
 * order-service's own copy of catalog-service's {@code ListingAvailability}
 * (catalog-service/src/main/java/it/rebyte/catalog/dto/ListingAvailability.java),
 * used only as the {@link it.rebyte.order.client.CatalogInternalClient}
 * deserialization target for {@code GET /internal/listings/{id}}. Field
 * names must match catalog's response JSON exactly. {@code shippingCost} is
 * {@code 0.00} for software; {@code version} is echoed back verbatim as
 * {@code expectedVersion} on reserve (D-17) so a price/status edit between
 * this read and the reserve call fences the reserve instead of charging a
 * stale price.
 */
public record CatalogListingResponse(String id, String sellerSub, String listingTitle, BigDecimal price,
        BigDecimal shippingCost, ListingType type, Set<PaymentMethod> paymentMethods, boolean available,
        long version, String licenceKeyPattern) {

    public CatalogListingResponse(String id, String sellerSub, String listingTitle, BigDecimal price,
            BigDecimal shippingCost, ListingType type, Set<PaymentMethod> paymentMethods, boolean available,
            long version) {
        this(id, sellerSub, listingTitle, price, shippingCost, type, paymentMethods, available, version, null);
    }
}
