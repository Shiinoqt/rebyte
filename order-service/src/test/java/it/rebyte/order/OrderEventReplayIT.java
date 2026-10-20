package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;

import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.controller.OrderDeadLetterController;
import it.rebyte.order.controller.internal.OfferExpiryInternalController;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.outbox.OutboxRelay;
import it.rebyte.order.outbox.OutboxRepository;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.support.OrderIntegrationTestBase;
import org.springframework.transaction.PlatformTransactionManager;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@TestMethodOrder(OrderAnnotation.class)
class OrderEventReplayIT extends OrderIntegrationTestBase {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        registry.add("rebyte.outbox.relay.initial-delay-ms", () -> 3600000);
    }

    @Autowired
    private OrderRepository orders;

    @Autowired
    private OrderPaymentTransitionService transitions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderDeadLetterController replay;

    @Autowired
    private OfferExpiryInternalController offers;

    @Test
    @org.junit.jupiter.api.Order(2)
    void claimedCaptureRequiresMatchingTerminalNoChargeAndCannotRevive() {
        String orderId = seedHardwareOrder();
        PaymentResultEvent unknown = result(orderId, false, "OP-" + orderId);
        assertThatThrownBy(() -> transitions.apply("payment.failed", unknown))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(orders.findById(orderId).orElseThrow().getActiveListingGuard()).isNotNull();
        assertThat(count("order_inbox_events", "event_id", unknown.eventId())).isZero();

        PaymentResultEvent wrongOperation = result(orderId, true, "wrong-operation");
        assertThatThrownBy(() -> transitions.apply("payment.failed", wrongOperation))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);

        PaymentResultEvent terminal = result(orderId, true, "OP-" + orderId);
        transitions.apply("payment.failed", terminal);
        transitions.apply("payment.failed", terminal);
        Order cancelled = orders.findById(orderId).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(cancelled.getCheckoutPhase()).isEqualTo(CheckoutPhase.CANCELLED);
        assertThat(cancelled.getActiveListingGuard()).isNull();
        assertThat(count("order_outbox_events", "aggregate_id", orderId)).isEqualTo(1);

        assertThatThrownBy(() -> transitions.apply("payment.succeeded", result(orderId, false, "OP-" + orderId)))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(count("order_outbox_events", "aggregate_id", orderId)).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.Order(1)
    void refundClearsGuardAndTwoRelaysPublishEachOrderEventOnce() throws Exception {
        String orderId = seedHardwareOrder();
        transitions.apply("payment.succeeded", result(orderId, false, "OP-" + orderId));
        transitions.apply("payment.refunded", result(orderId, false, "OP-" + orderId));
        assertThat(orders.findById(orderId).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(orders.findById(orderId).orElseThrow().getActiveListingGuard()).isNull();
        assertThat(count("order_outbox_events", "aggregate_id", orderId)).isEqualTo(2);

        for (int index = 0; index < 5; index++) {
            String extraOrderId = seedHardwareOrder();
            transitions.apply("payment.succeeded", result(extraOrderId, false, "OP-" + extraOrderId));
            transitions.apply("payment.refunded", result(extraOrderId, false, "OP-" + extraOrderId));
        }
        assertThat(outbox.countByPublishedAtIsNull()).isEqualTo(12);
        List<String> expectedIds = jdbc.queryForList("select json_unquote(json_extract(payload, '$.eventId')) "
                + "from order_outbox_events where published_at is null", String.class);
        assertThat(expectedIds).hasSize(12).doesNotHaveDuplicates();
        var claimPlan = jdbc.queryForList("explain select * from order_outbox_events "
                + "force index (idx_order_outbox_unpublished) where published_at is null "
                + "order by occurred_at, id limit 1 for update skip locked");

        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> heldId = new AtomicReference<>();
        OutboxRepository heldRepository = mock(OutboxRepository.class, AdditionalAnswers.delegatesTo(outbox));
        doAnswer(invocation -> {
            var rows = outbox.claimBatch(invocation.getArgument(0, Pageable.class));
            assertThat(rows).hasSize(1);
            heldId.set(mapper.readTree(rows.getFirst().getPayload()).get("eventId").asText());
            claimed.countDown();
            if (!release.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Held relay claim was not released within 30 seconds");
            }
            return rows;
        }).when(heldRepository).claimBatch(any());

        String testQueue = "test.order-events." + UUID.randomUUID();
        rabbit.execute(channel -> {
            channel.queueDeclare(testQueue, false, false, false, null);
            channel.queueBind(testQueue, "marketplace.events", "order.*");
            return null;
        });
        try {
            OutboxRelay held = new OutboxRelay(heldRepository, rabbit, transactionManager, 1, 5000);
            OutboxRelay other = new OutboxRelay(outbox, rabbit, transactionManager, 1, 5000);
            List<String> receivedIds = new ArrayList<>();
            try (var workers = Executors.newFixedThreadPool(2)) {
                var first = workers.submit(held::publishNextBatch);
                try {
                    assertThat(claimed.await(10, TimeUnit.SECONDS)).isTrue();
                    var second = workers.submit(() -> {
                        int published = 0;
                        for (int batch = 0; batch < 12 && other.publishNextBatch(); batch++) {
                            published++;
                        }
                        return published;
                    });
                    assertThat(second.get(20, TimeUnit.SECONDS)).as("Claim query plan: %s", claimPlan).isEqualTo(11);
                    for (int index = 0; index < 11; index++) {
                        var message = rabbit.receive(testQueue, 5000);
                        assertThat(message).isNotNull();
                        String eventId = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                                .get("eventId").asText();
                        assertThat(eventId).isNotEqualTo(heldId.get());
                        receivedIds.add(eventId);
                    }
                    assertThat(receivedIds).doesNotHaveDuplicates();
                    assertThat(rabbit.receive(testQueue, 500)).isNull();
                } finally {
                    release.countDown();
                }
                assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
                var message = rabbit.receive(testQueue, 5000);
                assertThat(message).isNotNull();
                String eventId = mapper.readTree(new String(message.getBody(), StandardCharsets.UTF_8))
                        .get("eventId").asText();
                assertThat(eventId).isEqualTo(heldId.get());
                receivedIds.add(eventId);
            }
            assertThat(outbox.countByPublishedAtIsNull()).isZero();
            assertThat(rabbit.receive(testQueue, 500)).isNull();
            assertThat(receivedIds).hasSize(12).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(expectedIds);
        } finally {
            rabbit.execute(channel -> channel.queueDelete(testQueue));
        }
    }

    @Test
    @org.junit.jupiter.api.Order(3)
    void adminReplaysOneParkedMessageAndOfferExpiryHookIsEmpty() throws InterruptedException {
        assertThat(offers.expireDue().expiredCount()).isZero();
        rabbit.convertAndSend("marketplace.events", "payment.expired", "parked");
        awaitQueueDepth(RabbitConfig.PAYMENT_RESULT_QUEUE, 1);
        rabbit.execute(channel -> {
            var delivery = channel.basicGet(RabbitConfig.PAYMENT_RESULT_QUEUE, false);
            channel.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
            return null;
        });
        awaitQueueDepth(RabbitConfig.PAYMENT_RESULT_DLQ, 1);
        Jwt buyer = jwt(false);
        assertThatThrownBy(() -> replay.replayOne(buyer, RabbitConfig.PAYMENT_RESULT_DLQ))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        Jwt admin = jwt(true);
        assertThat(replay.replayOne(admin, RabbitConfig.PAYMENT_RESULT_DLQ).getStatusCode().value()).isEqualTo(200);
        assertThat(replay.replayOne(admin, RabbitConfig.PAYMENT_RESULT_DLQ).getStatusCode().value()).isEqualTo(204);
        var replayed = rabbit.receive(RabbitConfig.PAYMENT_RESULT_QUEUE, 5000);
        assertThat(replayed).isNotNull();
        assertThat(replayed.getMessageProperties().getReceivedRoutingKey()).isEqualTo("payment.expired");
        assertThat(rabbit.receive(RabbitConfig.PAYMENT_RESULT_QUEUE, 500)).isNull();
    }

    private void awaitQueueDepth(String queue, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        long depth = -1;
        while (System.nanoTime() < deadline) {
            depth = rabbit.execute(channel -> channel.messageCount(queue));
            if (depth == expected) {
                return;
            }
            Thread.sleep(100);
        }
        assertThat(depth).isEqualTo(expected);
    }

    private String seedHardwareOrder() {
        String orderId = UUID.randomUUID().toString();
        String listingId = UUID.randomUUID().toString();
        Order order = new Order(orderId, "buyer", "seller", listingId, ListingType.HARDWARE, "Private title",
                new BigDecimal("12.37"), new BigDecimal("4.19"), new BigDecimal("16.56"), PaymentMethod.PAYPAL,
                "idem-" + orderId, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800));
        order.setActiveListingGuard(listingId);
        order.setReservationId(orderId);
        orders.saveAndFlush(order);
        jdbc.update("update orders set checkout_phase = 'CAPTURE_AUTHORIZED', provider_order_id = ?, "
                + "capture_operation_id = ? where id = ?", "PP-" + orderId, "OP-" + orderId, orderId);
        return orderId;
    }

    private PaymentResultEvent result(String orderId, boolean terminal, String operationId) {
        return new PaymentResultEvent(UUID.randomUUID().toString(), orderId, "PP-" + orderId, "CAP-1",
                operationId, terminal, Instant.now());
    }

    private Jwt jwt(boolean admin) {
        Map<String, Object> claims = admin ? Map.of("sub", "operator", "cognito:groups", List.of("ADMIN"))
                : Map.of("sub", "buyer");
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "none"), claims);
    }

    private long count(String table, String column, String id) {
        return jdbc.queryForObject("select count(*) from " + table + " where " + column + " = ?", Long.class, id);
    }
}
