package it.rebyte.order.messaging;

import java.time.Instant;

public record PaymentResultEvent(String eventId, String orderId, String providerOrderId,
        String providerCaptureId, String captureOperationId, boolean terminalNoCharge, Instant occurredAt) {
}
