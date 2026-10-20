package it.rebyte.messaging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Example;
import org.springframework.data.domain.ExampleMatcher;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.Message;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.messaging.OfferEvent;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.repository.ConversationRepository;
import it.rebyte.messaging.repository.MessageRepository;

@Service
public class OfferCardService {

    private static final int MAX_ATTEMPTS = 3;

    private final ConversationRepository conversations;
    private final MessageRepository messages;
    private final MongoTx tx;
    private final ConversationWriter writer;
    private final MessageViews views;
    private final RealtimePublisher realtime;
    private final NotificationPushes pushes;

    public OfferCardService(ConversationRepository conversations, MessageRepository messages, MongoTx tx,
            ConversationWriter writer, MessageViews views, RealtimePublisher realtime, NotificationPushes pushes) {
        this.conversations = conversations;
        this.messages = messages;
        this.tx = tx;
        this.writer = writer;
        this.views = views;
        this.realtime = realtime;
        this.pushes = pushes;
    }

    public void apply(String cardEvent, OfferEvent event) {
        Message.OfferEvent transition = Message.OfferEvent.valueOf(cardEvent);
        boolean buyerSends = switch (transition) {
            case CREATED, WITHDRAWN, EXPIRED -> true;
            case ACCEPTED, REJECTED -> false;
        };
        String sender = buyerSends ? event.buyerSub() : event.sellerSub();
        String recipient = buyerSends ? event.sellerSub() : event.buyerSub();
        Message.OfferCard card = new Message.OfferCard(event.offerId(), transition, event.status(), event.amount(),
                event.askingPrice(), event.expiresAt(), event.rejectionReason());
        ConversationWriter.Appended appended;
        for (int attempt = 1; ; attempt++) {
            try {
                appended = tx.execute(() -> {
                    Conversation conversation = conversations.findByListingIdAndBuyerSub(event.listingId(), event.buyerSub())
                            .orElseGet(() -> conversations.insert(new Conversation(UUID.randomUUID().toString(),
                                    event.listingId(), "HARDWARE", event.buyerSub(), event.sellerSub(), event.listingTitle(),
                                    event.askingPrice(), Instant.now().truncatedTo(ChronoUnit.MILLIS), null, null,
                                    0, 0, 0, null, null, null, null, 0, 0)));
                    return writer.append(conversation.getId(),
                            new MessageDraft(sender, recipient, MessageKind.OFFER, null, null, card, event.eventId()), null);
                });
                break;
            } catch (DuplicateKeyException duplicate) {
                // A duplicate aborts the Mongo transaction; inspect the committed winner outside it.
                if (alreadyApplied(event.eventId())) {
                    return;
                }
                if (attempt >= MAX_ATTEMPTS) {
                    throw duplicate;
                }
            }
        }
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("type", "message");
        push.put("conversationId", appended.conversation().getId());
        push.put("message", views.of(appended.message()));
        realtime.push(recipient, RealtimePublisher.MESSAGES, push);
        pushes.unreadChanged(recipient);
    }

    private boolean alreadyApplied(String eventId) {
        Message probe = new Message(null, null, 0, null, null, null, null, null, null, eventId, null, false, null);
        // Primitive defaults must not restrict the lookup to seq 0 or unread cards.
        return messages.exists(Example.of(probe, ExampleMatcher.matching().withIgnorePaths("seq", "read")));
    }
}
