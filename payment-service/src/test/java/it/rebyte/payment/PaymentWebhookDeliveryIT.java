package it.rebyte.payment;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentMethod;
import it.rebyte.payment.outbox.OutboxRelay;
import it.rebyte.payment.outbox.OutboxRepository;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.service.PaymentWebhookService;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

@SpringBootTest
@ActiveProfiles({"local-auth", "test"})
@TestPropertySource(properties = {"rebyte.paypal.webhook-id=test-webhook",
        "rebyte.outbox.relay.fixed-delay-ms=3600000", "rebyte.outbox.relay.initial-delay-ms=3600000"})
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
@TestMethodOrder(OrderAnnotation.class)
class PaymentWebhookDeliveryIT extends PaymentIntegrationTestBase {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private PaymentWebhookService webhooks;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ConnectionFactory connectionFactory;

    @Test
    @Order(1)
    void concurrentDuplicateAndTwoRelaysDeliverCompletedAndRefundedOnce() throws Exception {
        String orderId = newPayment();
        String captureId = "CAP-" + UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        byte[] completed = captureEvent(eventId, "COMPLETED", orderId, captureId);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> webhooks.acceptVerified(completed));
            var second = workers.submit(() -> webhooks.acceptVerified(completed));
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(count("payment_outbox_events", "aggregate_id", orderId)).isEqualTo(1);

        byte[] refunded = ("{\"id\":\"" + UUID.randomUUID() + "\","
                + "\"event_type\":\"PAYMENT.CAPTURE.REFUNDED\",\"resource\":{\"id\":\"REF-1\","
                + "\"amount\":{\"currency_code\":\"EUR\",\"value\":\"16.56\"},"
                + "\"links\":[{\"rel\":\"up\",\"href\":\"https://api.sandbox.paypal.com/v2/payments/captures/"
                + captureId + "\"}]}}")
                .getBytes(StandardCharsets.UTF_8);
        webhooks.acceptVerified(refunded);
        assertThat(count("payment_outbox_events", "aggregate_id", orderId)).isEqualTo(2);

        // payment-service does not own order-service's order.payment-result queue (CR-01);
        // this test proves relay behavior against a queue it declares for itself.
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        TopicExchange exchange = new TopicExchange(RebyteExchanges.MARKETPLACE_EVENTS, true, false);
        admin.declareExchange(exchange);
        Queue testQueue = new Queue("test.payment-result." + UUID.randomUUID(), true, false, false);
        admin.declareQueue(testQueue);
        admin.declareBinding(BindingBuilder.bind(testQueue).to(exchange).with("payment.*"));

        OutboxRelay secondRelay = new OutboxRelay(outbox, rabbit, transactionManager, 100, 5000);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(relay::publishNextBatch);
            var second = workers.submit(secondRelay::publishNextBatch);
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(outbox.countByPublishedAtIsNull()).isZero();
        Message one = rabbit.receive(testQueue.getName(), 5000);
        Message two = rabbit.receive(testQueue.getName(), 5000);
        assertThat(one).isNotNull();
        assertThat(two).isNotNull();
        assertThat(rabbit.receive(testQueue.getName(), 500)).isNull();
        assertThat(java.util.List.of(one.getMessageProperties().getReceivedRoutingKey(),
                two.getMessageProperties().getReceivedRoutingKey()))
                .containsExactlyInAnyOrder("payment.succeeded", "payment.refunded");
    }

    @Test
    @Order(2)
    void deniedPendingAndOtherEventsHaveExpectedEffectsAndBrokerFailureRetainsOutbox() {
        String orderId = newPayment();
        String captureId = "CAP-" + UUID.randomUUID();
        webhooks.acceptVerified(captureEvent(UUID.randomUUID().toString(), "PENDING", orderId, captureId));
        assertThat(count("payment_outbox_events", "aggregate_id", orderId)).isZero();
        webhooks.acceptVerified(captureEvent(UUID.randomUUID().toString(), "DENIED", orderId, captureId));
        assertThat(count("payment_outbox_events", "aggregate_id", orderId)).isEqualTo(1);
        webhooks.acceptVerified(("{\"id\":\"" + UUID.randomUUID() + "\",\"event_type\":\"IGNORED\"}")
                .getBytes(StandardCharsets.UTF_8));
        assertThat(count("payment_outbox_events", "aggregate_id", orderId)).isEqualTo(1);

        RabbitTemplate disconnected = new RabbitTemplate(new CachingConnectionFactory("localhost", 1));
        OutboxRelay failedRelay = new OutboxRelay(outbox, disconnected, transactionManager, 100, 500);
        org.assertj.core.api.Assertions.assertThatThrownBy(failedRelay::publishNextBatch)
                .isInstanceOf(RuntimeException.class);
        assertThat(outbox.countByPublishedAtIsNull()).isGreaterThanOrEqualTo(1);
    }

    private String newPayment() {
        String orderId = UUID.randomUUID().toString();
        Payment payment = new Payment(orderId, "buyer", new BigDecimal("12.37"), new BigDecimal("4.19"),
                new BigDecimal("16.56"), "Listing", PaymentMethod.PAYPAL, "create-" + orderId, Instant.now());
        payments.saveAndFlush(payment);
        payments.recordProviderOrder(orderId, "PP-" + orderId, "https://example.invalid/approve");
        return orderId;
    }

    private byte[] captureEvent(String eventId, String status, String orderId, String captureId) {
        return ("{\"id\":\"" + eventId + "\",\"event_type\":\"PAYMENT.CAPTURE." + status + "\","
                + "\"resource\":{\"id\":\"" + captureId + "\",\"custom_id\":\"" + orderId + "\","
                + "\"amount\":{\"currency_code\":\"EUR\",\"value\":\"16.56\"},"
                + "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"PP-" + orderId + "\"}}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private long count(String table, String column, String id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, id);
    }
}
