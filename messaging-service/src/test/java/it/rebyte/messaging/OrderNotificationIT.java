package it.rebyte.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.messaging.config.RabbitConfig;
import it.rebyte.messaging.domain.Notification;
import it.rebyte.messaging.domain.NotificationType;
import it.rebyte.messaging.domain.OrderNotificationEvent;
import it.rebyte.messaging.email.EmailJob;
import it.rebyte.messaging.email.EmailJobs;
import it.rebyte.messaging.email.EmailKind;
import it.rebyte.messaging.messaging.OrderEvent;
import it.rebyte.messaging.outbox.OutboxEvent;
import it.rebyte.messaging.service.MongoTx;
import it.rebyte.messaging.service.OrderNotificationService;
import it.rebyte.messaging.support.MessagingInfraTestBase;

@SpringBootTest
@ActiveProfiles("local-auth")
@TestPropertySource(properties = "rebyte.outbox.relay.fixed-delay-ms=3600000")
class OrderNotificationIT extends MessagingInfraTestBase {

    @Autowired MongoTemplate mongo;
    @Autowired RabbitTemplate rabbit;
    @Autowired RabbitAdmin rabbitAdmin;
    @Autowired ObjectMapper mapper;
    @Autowired OrderNotificationService service;
    @Autowired MongoTx transaction;
    @Autowired EmailJobs emails;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Notification.class);
        mongo.remove(new Query(), OutboxEvent.class);
        rabbitAdmin.purgeQueue(RabbitConfig.ORDER_NOTIFY_QUEUE, false);
        rabbitAdmin.purgeQueue(RabbitConfig.ORDER_NOTIFY_DLQ, false);
        rabbitAdmin.purgeQueue(RabbitConfig.EMAIL_SEND_QUEUE, false);
    }

    @Test
    void shipmentNotifiesOnlyTheBuyerWithTrackingAndSurvivesRedelivery() throws Exception {
        Map<String, Object> payload = payload("HARDWARE");
        publish("order.shipped", payload);
        publish("order.shipped", payload);
        drainOrders();

        List<Notification> notifications = notifications(payload);
        assertThat(notifications).hasSize(1);
        assertNotification(notifications.getFirst(), payload, "buyer-sub", OrderNotificationEvent.SHIPPED,
                Map.of("listingTitle", "Tastiera è 日本語", "carrier", "BRT", "trackingCode", "BRT-123 456"));
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @Test
    void paidNotifiesOnlyTheSellerAndEnqueuesOneBuyerEmailAcrossRedeliveries() throws Exception {
        Map<String, Object> payload = payload("HARDWARE");
        payload.remove("carrier");
        payload.remove("trackingCode");
        payload.put("reservationId", "reservation-1");
        payload.put("cancellationReason", null);
        publish("order.paid", payload);
        publish("order.paid", payload);
        drainOrders();

        List<Notification> notifications = notifications(payload);
        assertThat(notifications).hasSize(1);
        assertNotification(notifications.getFirst(), payload, "seller-sub", OrderNotificationEvent.PAID,
                Map.of("listingTitle", "Tastiera è 日本語", "listingType", "HARDWARE"));
        List<OutboxEvent> rows = mongo.findAll(OutboxEvent.class);
        assertThat(rows).hasSize(1);
        OutboxEvent row = rows.getFirst();
        assertThat(row.getRoutingKey()).isEqualTo("email.send");
        assertThat(row.getPublishedAt()).isNull();
        EmailJob job = mapper.readValue(row.getPayload(), EmailJob.class);
        assertThat(job.kind()).isEqualTo(EmailKind.ORDER_PAID);
        assertThat(job.recipientSub()).isEqualTo("buyer-sub");
        assertThat(job.dedupKey()).isEqualTo("ORDER:" + payload.get("eventId"));
        assertThat(job.data()).isEqualTo(Map.of("orderId", payload.get("orderId"), "listingId", "listing-1",
                "listingTitle", "Tastiera è 日本語", "listingType", "HARDWARE"));
        assertThat(row.getEventId()).isEqualTo(job.eventId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SOFTWARE", "HARDWARE"})
    void deliveryPromptsOnlyTheBuyerToReviewAndSoftwareToDownload(String listingType) throws Exception {
        Map<String, Object> payload = payload(listingType);
        publish("order.delivered", payload);
        publish("order.delivered", payload);
        drainOrders();

        List<Notification> notifications = notifications(payload);
        assertThat(notifications).hasSize(1);
        Map<String, String> snapshot = new HashMap<>(Map.of("listingTitle", "Tastiera è 日本語",
                "listingType", listingType, "reviewPrompt", "true"));
        if (listingType.equals("SOFTWARE")) {
            snapshot.put("readyToDownload", "true");
        }
        assertNotification(notifications.getFirst(), payload, "buyer-sub", OrderNotificationEvent.DELIVERED, snapshot);
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @Test
    void refundNotifiesBothPartiesOnceWithTheRefundedStage() throws Exception {
        Map<String, Object> payload = payload("HARDWARE");
        payload.remove("carrier");
        payload.remove("trackingCode");
        payload.put("reservationId", null);
        payload.put("cancellationReason", "REFUNDED");
        payload.put("refundedFromStatus", "DELIVERED");
        publish("order.refunded", payload);
        publish("order.refunded", payload);
        drainOrders();

        List<Notification> notifications = notifications(payload);
        assertThat(notifications).extracting(Notification::getRecipientSub)
                .containsExactlyInAnyOrder("buyer-sub", "seller-sub");
        notifications.forEach(notification -> assertNotification(notification, payload, notification.getRecipientSub(),
                OrderNotificationEvent.REFUNDED,
                Map.of("listingTitle", "Tastiera è 日本語", "refundedFromStatus", "DELIVERED")));
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @Test
    void openedProblemNotifiesOnlyTheSellerWithItsReason() throws Exception {
        Map<String, Object> payload = problemPayload();
        publish("order.problem.opened", payload);
        publish("order.problem.opened", payload);
        drainOrders();

        List<Notification> notifications = notifications(payload);
        assertThat(notifications).hasSize(1);
        assertNotification(notifications.getFirst(), payload, "seller-sub", OrderNotificationEvent.PROBLEM_OPENED,
                Map.of("listingTitle", "Tastiera è 日本語", "reason", "NOT_AS_DESCRIBED"));
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @Test
    void resolvedProblemNotifiesOnlyTheBuyerWithItsOutcome() throws Exception {
        Map<String, Object> payload = problemPayload();
        payload.put("status", "RESOLVED");
        payload.put("outcome", "REFUND");
        publish("order.problem.resolved", payload);
        publish("order.problem.resolved", payload);
        drainOrders();

        List<Notification> notifications = notifications(payload);
        assertThat(notifications).hasSize(1);
        assertNotification(notifications.getFirst(), payload, "buyer-sub", OrderNotificationEvent.PROBLEM_RESOLVED,
                Map.of("listingTitle", "Tastiera è 日本語", "outcome", "REFUND"));
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"order.cancelled", "licence.issued", "licence.revoked", "user.developer_activated"})
    void excludedEventsProduceNoNotificationOrEmail(String routingKey) throws Exception {
        Map<String, Object> payload = payload("HARDWARE");
        payload.put("reservationId", "reservation-1");
        payload.put("cancellationReason", "PAYMENT_FAILED");
        publish(routingKey, payload);
        drainOrders();

        assertThat(mongo.count(new Query(), Notification.class)).isZero();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
        for (String queue : RabbitConfig.DEAD_LETTER_ORIGINS.values()) {
            assertThat(rabbitAdmin.getQueueInfo(queue)).as(queue).isNotNull();
            assertThat(rabbitAdmin.getQueueInfo(queue).getMessageCount()).as(queue).isZero();
        }
        assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.ORDER_NOTIFY_DLQ).getMessageCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"eventId", "orderId", "buyerSub", "sellerSub"})
    void missingRequiredIdentifiersLandInTheOrderDlq(String field) throws Exception {
        Map<String, Object> payload = payload("HARDWARE");
        payload.remove(field);
        publish("order.shipped", payload);

        Message rejected = rabbit.receive(RabbitConfig.ORDER_NOTIFY_DLQ, 10_000);
        assertThat(rejected).isNotNull();
        assertThat(mapper.readTree(rejected.getBody())).isEqualTo(mapper.valueToTree(payload));
        drainOrders();
        assertThat(mongo.count(new Query(), Notification.class)).isZero();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isZero();
    }

    @Test
    void failedEmailEnqueueRollsBackTheSellerNotification() throws Exception {
        Map<String, Object> payload = payload("HARDWARE");
        String dedupKey = "ORDER:" + payload.get("eventId");
        transaction.run(() -> emails.enqueue(EmailJob.of(EmailKind.ORDER_PAID, "buyer-sub", dedupKey, Map.of())));
        OrderEvent event = mapper.convertValue(payload, OrderEvent.class);

        assertThatThrownBy(() -> service.apply(OrderNotificationEvent.PAID, event))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(notifications(payload)).isEmpty();
        assertThat(mongo.count(new Query(), OutboxEvent.class)).isEqualTo(1);
    }

    private static Map<String, Object> problemPayload() {
        Map<String, Object> payload = payload("HARDWARE");
        payload.remove("carrier");
        payload.remove("trackingCode");
        payload.put("problemId", "problem-1");
        payload.put("reason", "NOT_AS_DESCRIBED");
        payload.put("status", "OPEN");
        payload.put("outcome", null);
        return payload;
    }

    static Map<String, Object> payload(String listingType) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("eventId", UUID.randomUUID().toString());
        payload.put("orderId", UUID.randomUUID().toString());
        payload.put("listingId", "listing-1");
        payload.put("listingType", listingType);
        payload.put("buyerSub", "buyer-sub");
        payload.put("sellerSub", "seller-sub");
        payload.put("listingTitle", "Tastiera è 日本語");
        payload.put("carrier", "HARDWARE".equals(listingType) ? "BRT" : null);
        payload.put("trackingCode", "HARDWARE".equals(listingType) ? "BRT-123 456" : null);
        payload.put("occurredAt", "2026-10-05T10:00:00Z");
        return payload;
    }

    static void assertNotification(Notification notification, Map<String, Object> payload, String recipient,
            OrderNotificationEvent event, Map<String, String> snapshot) {
        assertThat(notification.getRecipientSub()).isEqualTo(recipient);
        assertThat(notification.getType()).isEqualTo(NotificationType.ORDER);
        assertThat(notification.getOrder().orderId()).isEqualTo(payload.get("orderId"));
        assertThat(notification.getOrder().event()).isEqualTo(event);
        assertThat(notification.getDedupKey()).isEqualTo("ORDER:" + payload.get("eventId"));
        assertThat(notification.getSnapshot()).isEqualTo(snapshot);
    }

    private List<Notification> notifications(Map<String, Object> payload) {
        return mongo.find(Query.query(Criteria.where("dedupKey").is("ORDER:" + payload.get("eventId"))),
                Notification.class);
    }

    private void publish(String routingKey, Map<String, Object> payload) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        rabbit.send(RebyteExchanges.MARKETPLACE_EVENTS, routingKey,
                new Message(mapper.writeValueAsBytes(payload), properties));
    }

    private void drainOrders() throws Exception {
        // A committed marker on the same FIFO consumer proves prior deliveries were processed.
        Map<String, Object> marker = payload("HARDWARE");
        publish("order.shipped", marker);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(notifications(marker)).hasSize(1);
            assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.ORDER_NOTIFY_QUEUE)).isNotNull();
            assertThat(rabbitAdmin.getQueueInfo(RabbitConfig.ORDER_NOTIFY_QUEUE).getMessageCount()).isZero();
        });
        mongo.remove(Query.query(Criteria.where("dedupKey").is("ORDER:" + marker.get("eventId"))), Notification.class);
    }
}
