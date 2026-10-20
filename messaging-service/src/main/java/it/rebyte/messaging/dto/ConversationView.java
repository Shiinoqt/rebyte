package it.rebyte.messaging.dto;

import java.math.BigDecimal;
import java.time.Instant;

import it.rebyte.messaging.domain.MessageKind;

public record ConversationView(String id, String listingId, String listingType, String listingTitle,
        BigDecimal listingPrice, String buyerSub, String sellerSub, String otherParticipantSub,
        String otherParticipantUsername, LastMessageView lastMessage, Instant lastMessageAt, long unreadFor,
        boolean partnerOnline, Instant createdAt) {

    public record LastMessageView(String messageId, String senderSub, MessageKind kind, String preview,
            int imageCount, Instant at) {
    }
}
