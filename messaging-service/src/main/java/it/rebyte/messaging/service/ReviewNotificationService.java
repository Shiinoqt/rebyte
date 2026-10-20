package it.rebyte.messaging.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.messaging.ReviewEvent;

@Service
public class ReviewNotificationService {

    private final NotificationWriter writer;
    private final NotificationPushes pushes;

    public ReviewNotificationService(NotificationWriter writer, NotificationPushes pushes) {
        this.writer = writer;
        this.pushes = pushes;
    }

    /**
     * A single upsert keyed by REVIEW:{reviewId} needs no transaction and turns a redelivered
     * review into a no-op (D-17).
     */
    public void created(ReviewEvent event) {
        Notification notification = NotificationFactory.review(event.sellerSub(), event.reviewId(),
                Map.of("rating", String.valueOf(event.rating())));
        if (writer.upsertIfAbsent(notification)) {
            pushes.created(List.of(notification));
        }
    }
}
