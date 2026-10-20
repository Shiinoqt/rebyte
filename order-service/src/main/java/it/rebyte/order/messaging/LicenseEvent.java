package it.rebyte.order.messaging;

import java.time.Instant;
import java.util.UUID;

import it.rebyte.order.domain.License;
import it.rebyte.order.domain.LicenseStatus;
import it.rebyte.order.domain.Order;

public record LicenseEvent(String eventId, String orderId, String listingId, String buyerSub, String sellerSub,
        LicenseStatus status, Instant occurredAt) {

    public static LicenseEvent of(License license, Instant occurredAt) {
        return new LicenseEvent(UUID.randomUUID().toString(), license.getOrderId(), license.getListingId(),
                license.getBuyerSub(), license.getSellerSub(), license.getStatus(), occurredAt);
    }

    public static LicenseEvent of(Order order, LicenseStatus status, Instant occurredAt) {
        return new LicenseEvent(UUID.randomUUID().toString(), order.getId(), order.getListingId(),
                order.getBuyerSub(), order.getSellerSub(), status, occurredAt);
    }
}
