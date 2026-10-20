package it.rebyte.messaging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import feign.FeignException;
import it.rebyte.messaging.client.CatalogServiceClient;
import it.rebyte.messaging.client.ListingAvailability;
import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.dto.ConversationView;
import it.rebyte.messaging.dto.StartConversationRequest;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.error.CatalogUnavailableException;
import it.rebyte.messaging.error.ChatImageAlreadyAttachedException;
import it.rebyte.messaging.error.ConversationExistsException;
import it.rebyte.messaging.error.ConversationNotFoundException;
import it.rebyte.messaging.error.ListingNotAvailableException;
import it.rebyte.messaging.error.ListingNotFoundException;
import it.rebyte.messaging.error.SelfConversationException;
import it.rebyte.messaging.realtime.PresenceService;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.repository.ConversationRepository;

@Service
public class ConversationService {

    private final ConversationRepository conversations;
    private final CatalogServiceClient catalog;
    private final UsernameLookup usernames;
    private final MongoTx tx;
    private final ConversationWriter writer;
    private final EmailJobs emailJobs;
    private final ConversationViews conversationViews;
    private final MessageViews messageViews;
    private final RealtimePublisher realtime;
    private final NotificationPushes pushes;
    private final ChatImageService images;
    private final PresenceService presence;

    public ConversationService(ConversationRepository conversations, CatalogServiceClient catalog,
            UsernameLookup usernames, MongoTx tx, ConversationWriter writer, EmailJobs emailJobs,
            ConversationViews conversationViews, MessageViews messageViews, RealtimePublisher realtime,
            NotificationPushes pushes, ChatImageService images, PresenceService presence) {
        this.conversations = conversations;
        this.catalog = catalog;
        this.usernames = usernames;
        this.tx = tx;
        this.writer = writer;
        this.emailJobs = emailJobs;
        this.conversationViews = conversationViews;
        this.messageViews = messageViews;
        this.realtime = realtime;
        this.pushes = pushes;
        this.images = images;
        this.presence = presence;
    }

    /** D-32: one conversation per (listing, buyer), opened with its first message on an available listing. */
    public ConversationView start(String callerSub, StartConversationRequest request) {
        String text = MessageTextRules.normalize(request.text());
        conversations.findByListingIdAndBuyerSub(request.listingId(), callerSub).ifPresent(existing -> {
            throw new ConversationExistsException(existing.getId());
        });
        ListingAvailability listing = availability(request.listingId());
        if (callerSub.equals(listing.sellerSub())) {
            throw new SelfConversationException();
        }
        if (!listing.available()) {
            throw new ListingNotAvailableException();
        }
        List<String> imageKeys = images.validateAttachments(callerSub, request.imageKeys());
        String callerUsername = usernames.username(callerSub);

        ConversationWriter.Appended appended;
        try {
            appended = tx.execute(() -> {
                Conversation conversation = conversations.insert(new Conversation(UUID.randomUUID().toString(),
                        listing.id(), listing.type(), callerSub, listing.sellerSub(), listing.listingTitle(),
                        listing.price(), Instant.now().truncatedTo(ChronoUnit.MILLIS), null, null, 0, 0, 0, null,
                        null, null, null, 0, 0));
                Map<String, String> snapshot = new HashMap<>();
                snapshot.put("actorUsername", callerUsername);
                snapshot.put("listingTitle", listing.listingTitle());
                ConversationWriter.Appended result = writer.append(conversation.getId(),
                        new MessageDraft(callerSub, listing.sellerSub(), MessageKind.CHAT, text, imageKeys, null, null),
                        snapshot);
                Map<String, String> emailData = new HashMap<>();
                emailData.put("conversationId", conversation.getId());
                emailData.put("listingTitle", listing.listingTitle());
                emailData.put("senderUsername", callerUsername);
                emailData.values().removeIf(value -> value == null);
                emailJobs.enqueue(EmailJob.of(EmailKind.NEW_MESSAGE, listing.sellerSub(),
                        "MESSAGE:" + result.message().getId(), emailData));
                return result;
            });
        } catch (DuplicateKeyException duplicate) {
            // Pitfall 4: the losing concurrent start reads the winner only after its own transaction has aborted.
            // Without a winner the duplicate came from the unique image key index instead.
            String existingId = conversations.findByListingIdAndBuyerSub(request.listingId(), callerSub)
                    .map(Conversation::getId)
                    .orElseThrow(ChatImageAlreadyAttachedException::new);
            throw new ConversationExistsException(existingId);
        }

        Map<String, Object> push = new LinkedHashMap<>();
        push.put("type", "message");
        push.put("conversationId", appended.conversation().getId());
        push.put("message", messageViews.of(appended.message()));
        realtime.push(listing.sellerSub(), RealtimePublisher.MESSAGES, push);
        if (appended.notification() != null) {
            pushes.created(List.of(appended.notification()));
        }
        return conversationViews.of(appended.conversation(), callerSub, usernames.bestEffortUsername(listing.sellerSub()),
                presence.isOnline(listing.sellerSub()));
    }

    public ConversationView get(String callerSub, String id) {
        Conversation conversation = conversations.findById(id)
                .filter(found -> found.isParticipant(callerSub))
                .orElseThrow(ConversationNotFoundException::new);
        String partner = conversation.otherParticipant(callerSub);
        return conversationViews.of(conversation, callerSub, usernames.bestEffortUsername(partner),
                presence.isOnline(partner));
    }

    private ListingAvailability availability(String listingId) {
        try {
            return catalog.availability(listingId);
        } catch (RuntimeException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof FeignException.NotFound) {
                    throw new ListingNotFoundException();
                }
            }
            throw new CatalogUnavailableException();
        }
    }
}
