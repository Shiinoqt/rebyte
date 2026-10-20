package it.rebyte.messaging.service;

import java.util.List;

import org.springframework.stereotype.Component;

import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.dto.MessageView;

@Component
public class MessageViews {

    private final ChatImageService images;

    public MessageViews(ChatImageService images) {
        this.images = images;
    }

    public MessageView of(Message message) {
        Message.OfferCard card = message.getOffer();
        MessageView.Offer offer = card == null ? null : new MessageView.Offer(card.offerId(), card.event(), card.status(),
                card.amount(), card.askingPrice(), card.expiresAt(), card.rejectionReason());
        List<MessageView.ImageView> imageViews = message.getImageKeys() == null ? List.of()
                : message.getImageKeys().stream().map(images::presignedGet).toList();
        return new MessageView(message.getId(), message.getConversationId(), message.getSeq(), message.getSenderSub(),
                message.getKind(), message.getText(), offer, imageViews, message.getCreatedAt());
    }
}
