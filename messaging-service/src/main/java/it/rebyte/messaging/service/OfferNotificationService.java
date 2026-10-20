package it.rebyte.messaging.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.messaging.OfferEvent;

@Service
public class OfferNotificationService {

    private final UsernameLookup usernames;
    private final NotificationWriter writer;
    private final NotificationPushes pushes;

    public OfferNotificationService(UsernameLookup usernames, NotificationWriter writer, NotificationPushes pushes) {
        this.usernames = usernames;
        this.writer = writer;
        this.pushes = pushes;
    }

    public void received(OfferEvent event) {
        Map<String, String> snapshot = snapshot(event, event.buyerSub());
        snapshot.put("askingPrice", event.askingPrice() == null ? null : event.askingPrice().toPlainString());
        insert(NotificationFactory.offerReceived(event.sellerSub(), event.offerId(), snapshot));
    }

    public void accepted(OfferEvent event) {
        Map<String, String> snapshot = snapshot(event, event.sellerSub());
        snapshot.put("expiresAt", event.expiresAt() == null ? null : event.expiresAt().toString());
        insert(NotificationFactory.offerAccepted(event.buyerSub(), event.offerId(), snapshot));
    }

    private Map<String, String> snapshot(OfferEvent event, String actorSub) {
        Map<String, String> snapshot = new HashMap<>();
        snapshot.put("actorUsername", usernames.username(actorSub));
        snapshot.put("listingTitle", event.listingTitle());
        snapshot.put("amount", event.amount() == null ? null : event.amount().toPlainString());
        return snapshot;
    }

    private void insert(Notification notification) {
        if (writer.upsertIfAbsent(notification)) {
            pushes.created(List.of(notification));
        }
    }
}
