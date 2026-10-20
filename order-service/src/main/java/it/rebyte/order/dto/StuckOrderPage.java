package it.rebyte.order.dto;

import java.time.Instant;
import java.util.List;

public record StuckOrderPage(List<Item> items, String nextCursor) {

    public record Item(String orderId, String category, Instant createdAt, Instant expiryAt,
            String cancellationReason, String lastPayPalStatus) {
    }
}
