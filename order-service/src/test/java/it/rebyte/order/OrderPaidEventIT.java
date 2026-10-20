package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.controller.OrderDeadLetterController;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.License;
import it.rebyte.order.repository.LicenseRepository;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest
class OrderPaidEventIT extends OrderIntegrationTestBase {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> true);
        registry.add("spring.rabbitmq.listener.simple.concurrency", () -> 1);
        registry.add("spring.rabbitmq.listener.simple.max-concurrency", () -> 1);
        registry.add("rebyte.outbox.relay.initial-delay-ms", () -> 3600000);
    }

    @Autowired
    private OrderRepository orders;

    @Autowired
    private LicenseRepository licenses;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private OrderPaymentTransitionService transitions;

    @Autowired
    private OrderDeadLetterController deadLetters;

    @BeforeEach
    void clearPaymentQueuesBeforeTest() throws InterruptedException {
        purgePaymentQueues();
    }

    @AfterEach
    void clearPaymentQueuesAfterTest() throws InterruptedException {
        purgePaymentQueues();
    }

    private void purgePaymentQueues() throws InterruptedException {
        rabbit.execute(channel -> {
            channel.queuePurge(RabbitConfig.PAYMENT_RESULT_QUEUE);
            channel.queuePurge(RabbitConfig.PAYMENT_RESULT_DLQ);
            return null;
        });
        awaitQueueDepth(RabbitConfig.PAYMENT_RESULT_QUEUE, 0);
        awaitDeadLetters(0);
    }

    @Test
    void onlyPaymentEventMarksOrderPaidAndEmitsOneMinimalLifecycleEvent() throws Exception {
        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "buyer", "seller", UUID.randomUUID().toString(), ListingType.SOFTWARE,
                "Private title", new BigDecimal("12.37"), BigDecimal.ZERO, new BigDecimal("12.37"),
                PaymentMethod.PAYPAL, "idem-" + orderId, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        orders.saveAndFlush(order);
        String providerOrderId = "PP-" + orderId;
        String operationId = "OP-" + orderId;
        jdbc.update("update orders set checkout_phase = 'CAPTURE_AUTHORIZED', provider_order_id = ?, "
                + "capture_operation_id = ? where id = ?", providerOrderId, operationId, orderId);

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        String eventId = UUID.randomUUID().toString();
        String payload = mapper.writeValueAsString(Map.of("eventId", eventId, "orderId", orderId,
                "providerOrderId", providerOrderId, "providerCaptureId", "CAP-1",
                "captureOperationId", operationId, "terminalNoCharge", false,
                "occurredAt", Instant.now().toString()));
        rabbit.convertAndSend("marketplace.events", "payment.succeeded", payload);
        awaitStatus(orderId, OrderStatus.DELIVERED);
        rabbit.convertAndSend("marketplace.events", "payment.succeeded", payload);
        // One FIFO consumer must finish the duplicate before it can commit the sentinel.
        String sentinelId = UUID.randomUUID().toString();
        Order sentinel = new Order(sentinelId, "buyer", "seller", UUID.randomUUID().toString(), ListingType.SOFTWARE,
                "Sentinel", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                "idem-" + sentinelId, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800));
        orders.saveAndFlush(sentinel);
        jdbc.update("update orders set checkout_phase='CAPTURE_AUTHORIZED', provider_order_id=?, "
                + "capture_operation_id=? where id=?", "PP-" + sentinelId, "OP-" + sentinelId, sentinelId);
        rabbit.convertAndSend("marketplace.events", "payment.succeeded", mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(), "orderId", sentinelId, "providerOrderId", "PP-" + sentinelId,
                "providerCaptureId", "CAP-sentinel", "captureOperationId", "OP-" + sentinelId,
                "terminalNoCharge", false, "occurredAt", Instant.now().toString())));
        awaitStatus(sentinelId, OrderStatus.DELIVERED);
        awaitDeadLetters(0);

        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.paid'", Long.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.delivered'", Long.class, orderId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'licence.issued'", Long.class, orderId)).isEqualTo(1);
        String lifecycle = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.paid'", String.class, orderId);
        License license = licenses.findByOrderId(orderId).orElseThrow();
        var event = mapper.readTree(lifecycle);
        assertThat(event.path("orderId").asText()).isEqualTo(orderId);
        assertThat(event.path("listingTitle").asText()).isEqualTo("Private title");
        assertThat(lifecycle).doesNotContain("price", "amount", "address", license.getKey());
        String delivered = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id = ? "
                + "and event_type = 'order.delivered'", String.class, orderId);
        var deliveredEvent = mapper.readTree(delivered);
        assertThat(deliveredEvent.path("listingTitle").asText()).isEqualTo("Private title");
        assertThat(deliveredEvent.path("carrier").isNull()).isTrue();
        assertThat(deliveredEvent.path("trackingCode").isNull()).isTrue();
        assertThat(delivered).doesNotContain("price", "amount", "address", license.getKey());
        for (String eventType : List.of("licence.issued")) {
            String eventPayload = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id = ? "
                    + "and event_type = ?", String.class, orderId, eventType);
            assertThat(eventPayload).doesNotContain("Private title", "price", "amount", "address", license.getKey());
        }
    }

    @Test
    void expiryForOrderCancelledBeforeProviderOrderWasRecordedIsNoOp() {
        String orderId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "buyer", "seller", UUID.randomUUID().toString(), ListingType.HARDWARE,
                "Listing", new BigDecimal("12.37"), new BigDecimal("4.19"), new BigDecimal("16.56"),
                PaymentMethod.PAYPAL, "idem-" + orderId, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        orders.saveAndFlush(order);
        jdbc.update("update orders set status = 'CANCELLED', checkout_phase = 'CANCELLED', "
                + "cancellation_reason = 'PAYMENT_UNAVAILABLE', active_listing_guard = null where id = ?", orderId);
        long outbox = jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ?",
                Long.class, orderId);

        transitions.apply("payment.expired", new PaymentResultEvent(UUID.randomUUID().toString(), orderId,
                "PP-" + orderId, null, null, true, Instant.now()));

        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id = ?",
                Long.class, orderId)).isEqualTo(outbox);
        assertThatThrownBy(() -> transitions.apply("payment.succeeded", new PaymentResultEvent(
                UUID.randomUUID().toString(), orderId, "PP-" + orderId, "CAP-1", null, false, Instant.now())))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void replayRedeliversAParkedEventUnderItsOriginalRoutingKey() throws Exception {
        String orderId = UUID.randomUUID().toString();
        String providerOrderId = "PP-" + orderId;
        String operationId = "OP-" + orderId;
        rabbit.convertAndSend("marketplace.events", "payment.succeeded", mapper.writeValueAsString(Map.of(
                "eventId", UUID.randomUUID().toString(), "orderId", orderId, "providerOrderId", providerOrderId,
                "providerCaptureId", "CAP-1", "captureOperationId", operationId, "terminalNoCharge", false,
                "occurredAt", Instant.now().toString())));
        awaitDeadLetters(1);

        Order order = new Order(orderId, "buyer", "seller", UUID.randomUUID().toString(), ListingType.SOFTWARE,
                "Listing", new BigDecimal("12.37"), BigDecimal.ZERO, new BigDecimal("12.37"),
                PaymentMethod.PAYPAL, "idem-" + orderId, UUID.randomUUID().toString(), Instant.now(),
                Instant.now().plusSeconds(1800));
        orders.saveAndFlush(order);
        jdbc.update("update orders set checkout_phase = 'CAPTURE_AUTHORIZED', provider_order_id = ?, "
                + "capture_operation_id = ? where id = ?", providerOrderId, operationId, orderId);

        assertThat(deadLetters.replayOne(admin(), RabbitConfig.PAYMENT_RESULT_DLQ).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        awaitStatus(orderId, OrderStatus.DELIVERED);
        awaitDeadLetters(0);
    }

    @Test
    void replayRefusesAParkedEventWithoutAnEventRoutingKey() throws Exception {
        rabbit.convertAndSend("", RabbitConfig.PAYMENT_RESULT_DLQ, "{\"eventId\":\"orphan\"}");
        awaitDeadLetters(1);

        assertThatThrownBy(() -> deadLetters.replayOne(admin(), RabbitConfig.PAYMENT_RESULT_DLQ))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        refused -> assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        awaitDeadLetters(1);
        assertThat(rabbit.receive(RabbitConfig.PAYMENT_RESULT_DLQ, 5000)).isNotNull();
        awaitDeadLetters(0);
    }

    private Jwt admin() {
        return Jwt.withTokenValue("admin").header("alg", "none").subject("admin")
                .claim("cognito:groups", List.of("ADMIN")).build();
    }

    private void awaitDeadLetters(int wanted) throws InterruptedException {
        awaitQueueDepth(RabbitConfig.PAYMENT_RESULT_DLQ, wanted);
    }

    private void awaitQueueDepth(String queue, int wanted) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        long depth = -1;
        while (System.nanoTime() < deadline) {
            depth = rabbit.execute(channel -> channel.messageCount(queue));
            if (depth == wanted) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(depth).isEqualTo(wanted);
    }

    private void awaitStatus(String orderId, OrderStatus wanted) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (orders.findById(orderId).orElseThrow().getStatus() == wanted) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(wanted);
    }
}
