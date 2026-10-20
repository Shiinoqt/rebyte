package it.rebyte.messaging.service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import it.rebyte.messaging.client.FollowersPage;
import it.rebyte.messaging.client.LicenceHolderPage;
import it.rebyte.messaging.client.OrderServiceClient;
import it.rebyte.messaging.client.UserServiceClient;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.messaging.VersionReleasedEvent;

@Service
public class ReleaseFanOutService {

    private final OrderServiceClient orders;
    private final UserServiceClient users;
    private final NotificationWriter writer;
    private final EmailJobs emails;
    private final NotificationPushes pushes;

    public ReleaseFanOutService(OrderServiceClient orders, UserServiceClient users, NotificationWriter writer,
            EmailJobs emails, NotificationPushes pushes) {
        this.orders = orders;
        this.users = users;
        this.writer = writer;
        this.emails = emails;
        this.pushes = pushes;
    }

    public int fanOut(VersionReleasedEvent event) {
        Map<String, String> snapshot = new HashMap<>();
        snapshot.put("listingTitle", event.listingTitle());
        snapshot.put("versionNumber", event.versionNumber());
        Map<String, String> data = new HashMap<>(snapshot);
        data.put("listingId", event.listingId());
        data.values().removeIf(value -> value == null);
        String cursor = null;
        int inserted = 0;
        do {
            LicenceHolderPage page = orders.holders(event.listingId(), cursor, 100);
            inserted += writePage(event, page.buyerSubs(), snapshot, data);
            cursor = page.nextCursor();
        } while (cursor != null);
        do {
            FollowersPage page = users.followers(event.sellerSub(), cursor, 500);
            inserted += writePage(event, page.subs(), snapshot, data);
            cursor = page.nextCursor();
        } while (cursor != null);
        return inserted;
    }

    private int writePage(VersionReleasedEvent event, List<String> subs, Map<String, String> snapshot,
            Map<String, String> data) {
        List<String> recipients = subs.stream().filter(sub -> sub != null && !sub.isBlank())
                .filter(sub -> !event.sellerSub().equals(sub)).distinct().toList();
        List<Notification> notifications = recipients.stream().map(sub -> NotificationFactory.release(
                sub, event.listingId(), event.versionNumber(), snapshot)).toList();
        List<Notification> inserted = writer.insertAllIgnoringDuplicates(notifications);
        pushes.created(inserted);
        String dedupKey = "RELEASE:" + event.listingId() + ":" + event.versionNumber();
        // Enqueue the entire page even on replay: an earlier attempt may have stored notifications only.
        emails.enqueueAllIgnoringDuplicates(recipients.stream().map(sub ->
                EmailJob.of(EmailKind.NEW_VERSION, sub, dedupKey, Map.copyOf(data))).toList());
        return inserted.size();
    }
}
