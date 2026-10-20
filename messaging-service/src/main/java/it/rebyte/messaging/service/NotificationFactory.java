package it.rebyte.messaging.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.Notification.FavouriteTarget;
import it.rebyte.messaging.domain.Notification.FollowTarget;
import it.rebyte.messaging.domain.Notification.MessageTarget;
import it.rebyte.messaging.domain.Notification.OrderTarget;
import it.rebyte.messaging.domain.Notification.PriceDropTarget;
import it.rebyte.messaging.domain.Notification.ReleaseTarget;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.domain.OrderNotificationEvent;

/**
 * The only way to build a {@link Notification}. Each method fills the single
 * target its type requires and the natural dedup key that identifies the
 * underlying event, so a redelivered event rebuilds the same key.
 *
 * <p>Instants are truncated to milliseconds because MongoDB stores dates with
 * millisecond precision; a key derived from a finer instant would differ
 * between the in-memory value and the stored one.
 */
public final class NotificationFactory {

    private NotificationFactory() {
    }

    public static Notification message(String recipientSub, String messageId, String conversationId, long seq,
            Map<String, String> snapshot) {
        requireText(recipientSub, "recipientSub");
        requireText(messageId, "messageId");
        requireText(conversationId, "conversationId");
        return build(recipientSub, NotificationType.MESSAGE, "MESSAGE:" + messageId, snapshot,
                new MessageTarget(messageId, conversationId, seq), null, null, null, null, null, null, null);
    }

    public static Notification favourite(String sellerSub, String favouriterSub, String listingId,
            Map<String, String> snapshot) {
        requireText(sellerSub, "sellerSub");
        requireText(favouriterSub, "favouriterSub");
        requireText(listingId, "listingId");
        return build(sellerSub, NotificationType.FAVOURITE, "FAVOURITE:" + favouriterSub + ":" + listingId, snapshot,
                null, new FavouriteTarget(favouriterSub, listingId), null, null, null, null, null, null);
    }

    public static Notification priceDrop(String recipientSub, String listingId, Instant changeInstant,
            Map<String, String> snapshot) {
        requireText(recipientSub, "recipientSub");
        requireText(listingId, "listingId");
        if (changeInstant == null) {
            throw new IllegalArgumentException("changeInstant must not be null");
        }
        Instant instant = changeInstant.truncatedTo(ChronoUnit.MILLIS);
        return build(recipientSub, NotificationType.PRICE_DROP, "PRICE_DROP:" + listingId + ":" + instant.toEpochMilli(),
                snapshot, null, null, new PriceDropTarget(listingId, instant), null, null, null, null, null);
    }

    public static Notification release(String recipientSub, String listingId, String versionNumber,
            Map<String, String> snapshot) {
        requireText(recipientSub, "recipientSub");
        requireText(listingId, "listingId");
        requireText(versionNumber, "versionNumber");
        return build(recipientSub, NotificationType.RELEASE, "RELEASE:" + listingId + ":" + versionNumber, snapshot,
                null, null, null, new ReleaseTarget(listingId, versionNumber), null, null, null, null);
    }

    public static Notification review(String sellerSub, String reviewId, Map<String, String> snapshot) {
        requireText(sellerSub, "sellerSub");
        requireText(reviewId, "reviewId");
        return build(sellerSub, NotificationType.REVIEW, "REVIEW:" + reviewId, snapshot,
                null, null, null, null, reviewId, null, null, null);
    }

    public static Notification follow(String followedSub, String followerSub, Map<String, String> snapshot) {
        requireText(followedSub, "followedSub");
        requireText(followerSub, "followerSub");
        return build(followedSub, NotificationType.FOLLOW, "FOLLOW:" + followerSub, snapshot,
                null, null, null, null, null, new FollowTarget(followerSub), null, null);
    }

    public static Notification offerReceived(String sellerSub, String offerId, Map<String, String> snapshot) {
        requireText(sellerSub, "sellerSub");
        requireText(offerId, "offerId");
        return build(sellerSub, NotificationType.OFFER_RECEIVED, "OFFER_RECEIVED:" + offerId, snapshot,
                null, null, null, null, null, null, offerId, null);
    }

    public static Notification offerAccepted(String buyerSub, String offerId, Map<String, String> snapshot) {
        requireText(buyerSub, "buyerSub");
        requireText(offerId, "offerId");
        return build(buyerSub, NotificationType.OFFER_ACCEPTED, "OFFER_ACCEPTED:" + offerId, snapshot,
                null, null, null, null, null, null, offerId, null);
    }

    public static Notification order(String recipientSub, String orderId, OrderNotificationEvent event,
            String sourceEventId, Map<String, String> snapshot) {
        requireText(recipientSub, "recipientSub");
        requireText(orderId, "orderId");
        requireText(sourceEventId, "sourceEventId");
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return build(recipientSub, NotificationType.ORDER, "ORDER:" + sourceEventId, snapshot,
                null, null, null, null, null, null, null, new OrderTarget(orderId, event));
    }

    private static Notification build(String recipientSub, NotificationType type, String dedupKey,
            Map<String, String> snapshot, MessageTarget message, FavouriteTarget favourite, PriceDropTarget priceDrop,
            ReleaseTarget release, String reviewId, FollowTarget follow, String offerId, OrderTarget order) {
        return new Notification(UUID.randomUUID().toString(), recipientSub, type,
                Instant.now().truncatedTo(ChronoUnit.MILLIS), false, null, null, dedupKey, copyWithoutNulls(snapshot),
                message, favourite, priceDrop, release, reviewId, follow, offerId, order);
    }

    private static Map<String, String> copyWithoutNulls(Map<String, String> snapshot) {
        if (snapshot == null) {
            return Map.of();
        }
        Map<String, String> copy = new HashMap<>();
        snapshot.forEach((key, value) -> {
            if (key != null && value != null) {
                copy.put(key, value);
            }
        });
        return Map.copyOf(copy);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
