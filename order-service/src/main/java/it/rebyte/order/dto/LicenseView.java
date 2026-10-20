package it.rebyte.order.dto;

import java.time.Instant;

import it.rebyte.order.domain.LicenseStatus;

public record LicenseView(String key, String orderId, String listingId, String listingTitle, LicenseStatus status,
        Instant issuedAt, Instant revokedAt) {
}
