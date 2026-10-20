package it.rebyte.messaging.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.OrderNotificationEvent;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.messaging.OrderEvent;

@Service
public class OrderNotificationService {

    private final MongoTx transaction;
    private final NotificationWriter writer;
    private final EmailJobs emails;
    private final NotificationPushes pushes;

    public OrderNotificationService(MongoTx transaction, NotificationWriter writer, EmailJobs emails,
            NotificationPushes pushes) {
        this.transaction = transaction;
        this.writer = writer;
        this.emails = emails;
        this.pushes = pushes;
    }

    public void apply(OrderNotificationEvent event, OrderEvent payload) {
        List<Notification> inserted = transaction.execute(() -> {
            List<Notification> created = new ArrayList<>();
            Map<String, String> snapshot = new HashMap<>();
            snapshot.put("listingTitle", payload.listingTitle());
            switch (event) {
                case PAID -> {
                    snapshot.put("listingType", payload.listingType());
                    if (insert(created, payload.sellerSub(), event, payload, snapshot)) {
                        Map<String, String> data = new HashMap<>();
                        data.put("orderId", payload.orderId());
                        data.put("listingId", payload.listingId());
                        data.put("listingTitle", payload.listingTitle());
                        data.put("listingType", payload.listingType());
                        emails.enqueue(EmailJob.of(EmailKind.ORDER_PAID, payload.buyerSub(),
                                "ORDER:" + payload.eventId(), data));
                    }
                }
                case SHIPPED -> {
                    snapshot.put("carrier", payload.carrier());
                    snapshot.put("trackingCode", payload.trackingCode());
                    insert(created, payload.buyerSub(), event, payload, snapshot);
                }
                case DELIVERED -> {
                    snapshot.put("listingType", payload.listingType());
                    snapshot.put("reviewPrompt", "true");
                    if ("SOFTWARE".equals(payload.listingType())) {
                        snapshot.put("readyToDownload", "true");
                    }
                    insert(created, payload.buyerSub(), event, payload, snapshot);
                }
                case REFUNDED -> {
                    snapshot.put("refundedFromStatus", payload.refundedFromStatus());
                    insert(created, payload.buyerSub(), event, payload, snapshot);
                    insert(created, payload.sellerSub(), event, payload, snapshot);
                }
                case PROBLEM_OPENED -> {
                    snapshot.put("reason", payload.reason());
                    insert(created, payload.sellerSub(), event, payload, snapshot);
                }
                case PROBLEM_RESOLVED -> {
                    snapshot.put("outcome", payload.outcome());
                    insert(created, payload.buyerSub(), event, payload, snapshot);
                }
            }
            return created;
        });
        pushes.created(inserted);
    }

    private boolean insert(List<Notification> created, String recipient, OrderNotificationEvent event,
            OrderEvent payload, Map<String, String> snapshot) {
        Notification notification = NotificationFactory.order(recipient, payload.orderId(), event,
                payload.eventId(), snapshot);
        if (!writer.upsertIfAbsent(notification)) {
            return false;
        }
        created.add(notification);
        return true;
    }
}
