package it.rebyte.messaging.service;

import org.springframework.stereotype.Component;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.dto.ConversationView;

@Component
public class ConversationViews {

    public ConversationView of(Conversation conversation, String viewerSub, String otherUsername,
            boolean partnerOnline) {
        Conversation.LastMessage last = conversation.getLastMessage();
        ConversationView.LastMessageView lastView = last == null ? null : new ConversationView.LastMessageView(
                last.messageId(), last.senderSub(), last.kind(), last.preview(), last.imageCount(), last.at());
        return new ConversationView(conversation.getId(), conversation.getListingId(), conversation.getListingType(),
                conversation.getListingTitle(), conversation.getListingPrice(), conversation.getBuyerSub(),
                conversation.getSellerSub(), conversation.otherParticipant(viewerSub), otherUsername, lastView,
                conversation.getLastMessageAt(), conversation.unreadFor(viewerSub), partnerOnline,
                conversation.getCreatedAt());
    }
}
