package it.rebyte.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import it.rebyte.messaging.domain.Message.OfferEvent;
import it.rebyte.messaging.domain.MessageKind;

public record MessageView(String id, String conversationId, long seq, String senderSub, MessageKind kind,
        String text, Offer offer, List<ImageView> images, Instant createdAt) {

    public record Offer(String offerId, OfferEvent event, String status, BigDecimal amount, BigDecimal askingPrice,
            Instant expiresAt, String rejectionReason) {
    }

    /** A short-lived presigned GET URL for one private chat image (D-35). */
    public record ImageView(String url, Instant expiresAt) {
    }
}
