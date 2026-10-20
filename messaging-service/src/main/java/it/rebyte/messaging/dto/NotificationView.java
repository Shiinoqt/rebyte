package it.rebyte.messaging.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;

public record NotificationView(String id, NotificationType type, Instant createdAt, boolean read, Instant readAt,
        Map<String, String> target, Map<String, String> snapshot) {

    public static NotificationView from(Notification notification) {
        return new NotificationView(notification.getId(), notification.getType(), notification.getCreatedAt(),
                notification.isRead(), notification.getReadAt(), target(notification),
                Map.copyOf(notification.getSnapshot()));
    }

    private static Map<String, String> target(Notification notification) {
        Map<String, String> values = new LinkedHashMap<>();
        switch (notification.getType()) {
            case MESSAGE -> {
                Notification.MessageTarget target = notification.getMessage();
                values.put("messageId", target.messageId());
                values.put("conversationId", target.conversationId());
                values.put("seq", Long.toString(target.seq()));
            }
            case FAVOURITE -> {
                Notification.FavouriteTarget target = notification.getFavourite();
                values.put("favouriterSub", target.favouriterSub());
                values.put("listingId", target.listingId());
            }
            case PRICE_DROP -> {
                Notification.PriceDropTarget target = notification.getPriceDrop();
                values.put("listingId", target.listingId());
                values.put("instant", target.instant().toString());
            }
            case RELEASE -> {
                Notification.ReleaseTarget target = notification.getRelease();
                values.put("listingId", target.listingId());
                values.put("versionNumber", target.versionNumber());
            }
            case REVIEW -> values.put("reviewId", notification.getReviewId());
            case FOLLOW -> values.put("followerSub", notification.getFollow().followerSub());
            case OFFER_RECEIVED, OFFER_ACCEPTED -> values.put("offerId", notification.getOfferId());
            case ORDER -> {
                Notification.OrderTarget target = notification.getOrder();
                values.put("orderId", target.orderId());
                values.put("event", target.event().name());
            }
        }
        return Map.copyOf(values);
    }
}
