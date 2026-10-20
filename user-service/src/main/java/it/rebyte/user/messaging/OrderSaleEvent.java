package it.rebyte.user.messaging;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSaleEvent(String orderId, String sellerSub, String refundedFromStatus, Instant occurredAt) {
}
