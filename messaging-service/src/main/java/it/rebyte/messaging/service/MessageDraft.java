package it.rebyte.messaging.service;

import java.util.List;

import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;

public record MessageDraft(String senderSub, String recipientSub, MessageKind kind, String text,
        List<String> imageKeys, Message.OfferCard offer, String sourceEventId) {
}
