package it.rebyte.messaging.service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Conversation;
import it.rebyte.messaging.domain.MessageKind;
import it.rebyte.messaging.dto.MessageView;
import it.rebyte.messaging.dto.SendMessageRequest;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.error.ChatImageAlreadyAttachedException;
import it.rebyte.messaging.error.ConversationNotFoundException;
import it.rebyte.messaging.realtime.RealtimePublisher;
import it.rebyte.messaging.repository.ConversationRepository;

/** Sends in an existing conversation never consult catalog-service, so a sold or removed listing keeps its chat (D-33). */
@Service
public class MessageService {

    private final ConversationRepository conversations;
    private final UsernameLookup usernames;
    private final MongoTx tx;
    private final ConversationWriter writer;
    private final EmailJobs emailJobs;
    private final MessageViews messageViews;
    private final RealtimePublisher realtime;
    private final NotificationPushes pushes;
    private final ChatImageService images;

    public MessageService(ConversationRepository conversations, UsernameLookup usernames, MongoTx tx,
            ConversationWriter writer, EmailJobs emailJobs, MessageViews messageViews, RealtimePublisher realtime,
            NotificationPushes pushes, ChatImageService images) {
        this.conversations = conversations;
        this.usernames = usernames;
        this.tx = tx;
        this.writer = writer;
        this.emailJobs = emailJobs;
        this.messageViews = messageViews;
        this.realtime = realtime;
        this.pushes = pushes;
        this.images = images;
    }

    public MessageView send(String callerSub, String conversationId, SendMessageRequest request) {
        Conversation conversation = conversations.findById(conversationId)
                .filter(found -> found.isParticipant(callerSub))
                .orElseThrow(ConversationNotFoundException::new);
        String rawText = request == null ? null : request.text();
        List<String> requestedImages = request == null ? null : request.imageKeys();
        boolean hasImages = requestedImages != null && !requestedImages.isEmpty();
        // D-34: after the first message an image-only send is allowed and stores no text.
        String text = hasImages && (rawText == null || rawText.isBlank()) ? null : MessageTextRules.normalize(rawText);
        List<String> imageKeys = images.validateAttachments(callerSub, requestedImages);
        String recipientSub = conversation.otherParticipant(callerSub);
        String senderUsername = usernames.username(callerSub);

        ConversationWriter.Appended appended;
        try {
            appended = tx.execute(() -> {
            Map<String, String> snapshot = new HashMap<>();
            snapshot.put("actorUsername", senderUsername);
            snapshot.put("listingTitle", conversation.getListingTitle());
            ConversationWriter.Appended result = writer.append(conversationId,
                    new MessageDraft(callerSub, recipientSub, MessageKind.CHAT, text, imageKeys, null, null), snapshot);
            Map<String, String> emailData = new HashMap<>();
            emailData.put("conversationId", conversationId);
            emailData.put("listingTitle", conversation.getListingTitle());
            emailData.put("senderUsername", senderUsername);
            emailData.values().removeIf(value -> value == null);
            emailJobs.enqueue(EmailJob.of(EmailKind.NEW_MESSAGE, recipientSub, "MESSAGE:" + result.message().getId(),
                    emailData));
            return result;
            });
        } catch (DuplicateKeyException imageAlreadyAttached) {
            throw new ChatImageAlreadyAttachedException();
        }

        MessageView view = messageViews.of(appended.message());
        Map<String, Object> push = new LinkedHashMap<>();
        push.put("type", "message");
        push.put("conversationId", conversationId);
        push.put("message", view);
        realtime.push(recipientSub, RealtimePublisher.MESSAGES, push);
        if (appended.notification() != null) {
            pushes.created(List.of(appended.notification()));
        }
        return view;
    }
}
