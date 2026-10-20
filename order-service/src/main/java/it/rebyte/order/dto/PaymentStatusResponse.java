package it.rebyte.order.dto;

public record PaymentStatusResponse(String orderId, String state, String providerOrderId,
        String lastPayPalStatus, boolean capturedAwaitingWebhook) {
}
