package it.rebyte.messaging.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.dto.NotificationView;
import it.rebyte.messaging.realtime.RealtimePublisher;

@Component
public class NotificationPushes {

    private final RealtimePublisher publisher;
    private final UnreadSummaryService unreadSummary;

    public NotificationPushes(RealtimePublisher publisher, UnreadSummaryService unreadSummary) {
        this.publisher = publisher;
        this.unreadSummary = unreadSummary;
    }

    /** Call after the transaction that wrote the notifications has committed. */
    public void created(Collection<Notification> notifications) {
        Set<String> recipients = new LinkedHashSet<>();
        for (Notification notification : notifications) {
            publisher.push(notification.getRecipientSub(), RealtimePublisher.NOTIFICATIONS,
                    NotificationView.from(notification));
            recipients.add(notification.getRecipientSub());
        }
        for (String recipient : recipients) {
            publisher.push(recipient, RealtimePublisher.UNREAD, unreadSummary.summary(recipient));
        }
    }

    public void unreadChanged(String sub) {
        publisher.push(sub, RealtimePublisher.UNREAD, unreadSummary.summary(sub));
    }
}
