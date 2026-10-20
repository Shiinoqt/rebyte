package it.rebyte.messaging.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * One notification for one recipient. Exactly one target is populated and it
 * matches {@link #getType()}; {@code NotificationFactory} is the only place
 * that builds instances.
 *
 * <p>The unique {@code (recipientSub, dedupKey)} index is what makes event
 * redelivery and fan-out reruns idempotent. The TTL index on {@code expiresAt}
 * only ever expires notifications that were read: unread ones carry no
 * {@code expiresAt}, so they are kept until the recipient reads them.
 */
@Document(collection = "notification")
@CompoundIndexes({
        @CompoundIndex(name = "recipient_dedup_unique", def = "{'recipientSub': 1, 'dedupKey': 1}", unique = true),
        @CompoundIndex(name = "recipient_unread_conversation_unique",
                def = "{'recipientSub': 1, 'message.conversationId': 1}", unique = true,
                partialFilter = "{'type': 'MESSAGE', 'read': false}"),
        @CompoundIndex(name = "recipient_created", def = "{'recipientSub': 1, 'createdAt': -1, '_id': -1}"),
        @CompoundIndex(name = "recipient_read_created", def = "{'recipientSub': 1, 'read': 1, 'createdAt': -1}"),
        @CompoundIndex(name = "recipient_type_favourite",
                def = "{'recipientSub': 1, 'type': 1, 'favourite.favouriterSub': 1, 'favourite.listingId': 1}"),
        @CompoundIndex(name = "recipient_type_follow", def = "{'recipientSub': 1, 'type': 1, 'follow.followerSub': 1}"),
        @CompoundIndex(name = "recipient_type_conversation_read",
                def = "{'recipientSub': 1, 'type': 1, 'message.conversationId': 1, 'read': 1}")
})
public class Notification {

    public record MessageTarget(String messageId, String conversationId, long seq) {
    }

    public record FavouriteTarget(String favouriterSub, String listingId) {
    }

    public record PriceDropTarget(String listingId, Instant instant) {
    }

    public record ReleaseTarget(String listingId, String versionNumber) {
    }

    public record FollowTarget(String followerSub) {
    }

    public record OrderTarget(String orderId, OrderNotificationEvent event) {
    }

    @Id
    private final String id;
    private final String recipientSub;
    private final NotificationType type;
    private final Instant createdAt;
    private boolean read;
    private Instant readAt;
    @Indexed(expireAfter = "0s")
    private Instant expiresAt;
    private final String dedupKey;
    private final Map<String, String> snapshot;
    private final MessageTarget message;
    private final FavouriteTarget favourite;
    private final PriceDropTarget priceDrop;
    private final ReleaseTarget release;
    private final String reviewId;
    private final FollowTarget follow;
    private final String offerId;
    private final OrderTarget order;

    public Notification(String id, String recipientSub, NotificationType type, Instant createdAt, boolean read,
            Instant readAt, Instant expiresAt, String dedupKey, Map<String, String> snapshot, MessageTarget message,
            FavouriteTarget favourite, PriceDropTarget priceDrop, ReleaseTarget release, String reviewId,
            FollowTarget follow, String offerId, OrderTarget order) {
        this.id = id;
        this.recipientSub = recipientSub;
        this.type = type;
        this.createdAt = createdAt;
        this.read = read;
        this.readAt = readAt;
        this.expiresAt = expiresAt;
        this.dedupKey = dedupKey;
        this.snapshot = snapshot;
        this.message = message;
        this.favourite = favourite;
        this.priceDrop = priceDrop;
        this.release = release;
        this.reviewId = reviewId;
        this.follow = follow;
        this.offerId = offerId;
        this.order = order;
    }

    public void markRead(Instant readAt, Duration retention) {
        this.read = true;
        this.readAt = readAt;
        this.expiresAt = readAt.plus(retention);
    }

    public String getId() {
        return id;
    }

    public String getRecipientSub() {
        return recipientSub;
    }

    public NotificationType getType() {
        return type;
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

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public String getDedupKey() {
        return dedupKey;
    }

    public Map<String, String> getSnapshot() {
        return snapshot;
    }

    public MessageTarget getMessage() {
        return message;
    }

    public FavouriteTarget getFavourite() {
        return favourite;
    }

    public PriceDropTarget getPriceDrop() {
        return priceDrop;
    }

    public ReleaseTarget getRelease() {
        return release;
    }

    public String getReviewId() {
        return reviewId;
    }

    public FollowTarget getFollow() {
        return follow;
    }

    public String getOfferId() {
        return offerId;
    }

    public OrderTarget getOrder() {
        return order;
    }
}
