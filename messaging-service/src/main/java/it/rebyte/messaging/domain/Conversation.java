package it.rebyte.messaging.domain;

import java.math.BigDecimal;
import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A conversation between the seller of a listing and one buyer. The unique
 * {@code (listingId, buyerSub)} index is what keeps concurrent starts down to
 * a single conversation.
 *
 * <p>{@code messageSeq} is the last sequence number handed out, and the unread
 * counters and email stamps are only ever changed by atomic updates, never by
 * saving a loaded copy. The email dispatch id is the in-flight slot of the
 * new-message email for that recipient and the generation advances when the
 * recipient reads, so a late send result cannot restart a throttle window that
 * a read already closed.
 */
@Document(collection = "conversation")
@CompoundIndexes({
        @CompoundIndex(name = "listing_buyer_unique", def = "{'listingId': 1, 'buyerSub': 1}", unique = true),
        @CompoundIndex(name = "buyer_last_message", def = "{'buyerSub': 1, 'lastMessageAt': -1, '_id': -1}"),
        @CompoundIndex(name = "seller_last_message", def = "{'sellerSub': 1, 'lastMessageAt': -1, '_id': -1}")
})
public class Conversation {

    /** The preview is at most 140 code points of text and null for image-only messages and offer cards. */
    public record LastMessage(String messageId, String senderSub, MessageKind kind, String preview, int imageCount,
            Instant at) {
    }

    private static final String BUYER_UNREAD = "buyerUnread";
    private static final String SELLER_UNREAD = "sellerUnread";
    private static final String BUYER_LAST_EMAIL = "buyerLastEmailAt";
    private static final String SELLER_LAST_EMAIL = "sellerLastEmailAt";

    @Id
    private final String id;
    private final String listingId;
    private final String listingType;
    private final String buyerSub;
    private final String sellerSub;
    private final String listingTitle;
    private final BigDecimal listingPrice;
    private final Instant createdAt;
    private final Instant lastMessageAt;
    private final LastMessage lastMessage;
    private final long messageSeq;
    private final long buyerUnread;
    private final long sellerUnread;
    private final Instant buyerLastEmailAt;
    private final Instant sellerLastEmailAt;
    private final String buyerEmailDispatchId;
    private final String sellerEmailDispatchId;
    private final long buyerEmailGeneration;
    private final long sellerEmailGeneration;

    public Conversation(String id, String listingId, String listingType, String buyerSub, String sellerSub,
            String listingTitle, BigDecimal listingPrice, Instant createdAt, Instant lastMessageAt,
            LastMessage lastMessage, long messageSeq, long buyerUnread, long sellerUnread, Instant buyerLastEmailAt,
            Instant sellerLastEmailAt, String buyerEmailDispatchId, String sellerEmailDispatchId,
            long buyerEmailGeneration, long sellerEmailGeneration) {
        this.id = id;
        this.listingId = listingId;
        this.listingType = listingType;
        this.buyerSub = buyerSub;
        this.sellerSub = sellerSub;
        this.listingTitle = listingTitle;
        this.listingPrice = listingPrice;
        this.createdAt = createdAt;
        this.lastMessageAt = lastMessageAt;
        this.lastMessage = lastMessage;
        this.messageSeq = messageSeq;
        this.buyerUnread = buyerUnread;
        this.sellerUnread = sellerUnread;
        this.buyerLastEmailAt = buyerLastEmailAt;
        this.sellerLastEmailAt = sellerLastEmailAt;
        this.buyerEmailDispatchId = buyerEmailDispatchId;
        this.sellerEmailDispatchId = sellerEmailDispatchId;
        this.buyerEmailGeneration = buyerEmailGeneration;
        this.sellerEmailGeneration = sellerEmailGeneration;
    }

    public boolean isParticipant(String sub) {
        return buyerSub.equals(sub) || sellerSub.equals(sub);
    }

    public String otherParticipant(String sub) {
        if (buyerSub.equals(sub)) {
            return sellerSub;
        }
        if (sellerSub.equals(sub)) {
            return buyerSub;
        }
        throw new IllegalArgumentException("not a participant of conversation " + id);
    }

    public long unreadFor(String sub) {
        return buyerSub.equals(sub) ? buyerUnread : sellerUnread;
    }

    /** Mongo field holding the unread counter of the given participant. */
    public static String unreadField(Conversation conversation, String sub) {
        return conversation.buyerSub.equals(sub) ? BUYER_UNREAD : SELLER_UNREAD;
    }

    /** Mongo field holding the last new-message email stamp of the given participant. */
    public static String lastEmailField(Conversation conversation, String sub) {
        return conversation.buyerSub.equals(sub) ? BUYER_LAST_EMAIL : SELLER_LAST_EMAIL;
    }

    public String getId() {
        return id;
    }

    public String getListingId() {
        return listingId;
    }

    public String getListingType() {
        return listingType;
    }

    public String getBuyerSub() {
        return buyerSub;
    }

    public String getSellerSub() {
        return sellerSub;
    }

    public String getListingTitle() {
        return listingTitle;
    }

    public BigDecimal getListingPrice() {
        return listingPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastMessageAt() {
        return lastMessageAt;
    }

    public LastMessage getLastMessage() {
        return lastMessage;
    }

    public long getMessageSeq() {
        return messageSeq;
    }

    public long getBuyerUnread() {
        return buyerUnread;
    }

    public long getSellerUnread() {
        return sellerUnread;
    }

    public Instant getBuyerLastEmailAt() {
        return buyerLastEmailAt;
    }

    public Instant getSellerLastEmailAt() {
        return sellerLastEmailAt;
    }

    public String getBuyerEmailDispatchId() {
        return buyerEmailDispatchId;
    }

    public String getSellerEmailDispatchId() {
        return sellerEmailDispatchId;
    }

    public long getBuyerEmailGeneration() {
        return buyerEmailGeneration;
    }

    public long getSellerEmailGeneration() {
        return sellerEmailGeneration;
    }
}
