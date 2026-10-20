package it.rebyte.messaging.messaging;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderEvent(String eventId, String orderId, String listingId, String listingType,
        String buyerSub, String sellerSub, String listingTitle, String carrier, String trackingCode,
        String refundedFromStatus, String reason, String outcome, Instant occurredAt) {
}
