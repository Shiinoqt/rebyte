package it.rebyte.messaging.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One message of a conversation. Messages are immutable apart from the read
 * flag. Null properties are not written, which is what lets the two
 * conditional unique indexes ignore chat messages without a source event and
 * messages without images: {@code sourceEventId} makes offer event redelivery
 * idempotent and {@code imageKeys} makes an uploaded image attach to a single
 * message.
 */
@Document(collection = "message")
@CompoundIndexes({
        @CompoundIndex(name = "conversation_seq_unique", def = "{'conversationId': 1, 'seq': 1}", unique = true),
        @CompoundIndex(name = "source_event_unique", def = "{'sourceEventId': 1}", unique = true,
                partialFilter = "{'sourceEventId': {$exists: true}}"),
        @CompoundIndex(name = "image_keys_unique", def = "{'imageKeys': 1}", unique = true,
                partialFilter = "{'imageKeys': {$exists: true}}"),
        @CompoundIndex(name = "conversation_recipient_read_seq",
                def = "{'conversationId': 1, 'recipientSub': 1, 'read': 1, 'seq': 1}")
})
public class Message {

    public enum OfferEvent {
        CREATED,
        ACCEPTED,
        REJECTED,
        WITHDRAWN,
        EXPIRED
    }

    public record OfferCard(String offerId, OfferEvent event, String status, BigDecimal amount,
            BigDecimal askingPrice, Instant expiresAt, String rejectionReason) {
    }

    @Id
    private final String id;
    private final String conversationId;
    private final long seq;
    private final String senderSub;
    private final String recipientSub;
    private final MessageKind kind;
    private final String text;
    private final List<String> imageKeys;
    private final OfferCard offer;
    private final String sourceEventId;
    private final Instant createdAt;
    private boolean read;
    private Instant readAt;

    public Message(String id, String conversationId, long seq, String senderSub, String recipientSub,
            MessageKind kind, String text, List<String> imageKeys, OfferCard offer, String sourceEventId,
            Instant createdAt, boolean read, Instant readAt) {
        this.id = id;
        this.conversationId = conversationId;
        this.seq = seq;
        this.senderSub = senderSub;
        this.recipientSub = recipientSub;
        this.kind = kind;
        this.text = text;
        this.imageKeys = imageKeys;
        this.offer = offer;
        this.sourceEventId = sourceEventId;
        this.createdAt = createdAt;
        this.read = read;
        this.readAt = readAt;
    }

    public String getId() {
        return id;
    }

    public String getConversationId() {
        return conversationId;
    }

    public long getSeq() {
        return seq;
    }

    public String getSenderSub() {
        return senderSub;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public MessageKind getKind() {
        return kind;
    }

    public String getText() {
        return text;
    }

    public List<String> getImageKeys() {
        return imageKeys;
    }

    public OfferCard getOffer() {
        return offer;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public boolean isRead() {
        return read;
    }

    public Instant getReadAt() {
        return readAt;
    }
}
