package it.rebyte.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.user.support.LocalAuthTokens;
import it.rebyte.user.config.RabbitConfig;
import it.rebyte.user.messaging.OrderSaleListener;
import it.rebyte.user.repository.SellerSaleRepository;
import it.rebyte.user.support.RabbitTestBase;

@SuppressWarnings("unchecked")
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
class SellerSaleProjectionIT extends RabbitTestBase {

    @DynamicPropertySource
    static void issuerMatchesReservedPort(DynamicPropertyRegistry registry) {
        int port = findFreePort();
        registry.add("server.port", () -> port);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "http://localhost:" + port + "/");
    }

    private static int findFreePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SellerSaleRepository sellerSaleRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private OrderSaleListener orderSaleListener;

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidSales")
    void invalidSaleFailsWithoutPersistingCorruptedData(String scenario, String orderId, String sellerSub,
            Class<? extends RuntimeException> exceptionType, int errorCode, String sqlState) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> assertStrictSqlMode());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status ->
                sellerSaleRepository.insertIfAbsent(orderId, sellerSub)))
                .isInstanceOf(exceptionType)
                .rootCause().isInstanceOfSatisfying(SQLException.class, sqlError -> {
                    assertThat(sqlError.getErrorCode()).isEqualTo(errorCode);
                    assertThat(sqlError.getSQLState()).isEqualTo(sqlState);
                });

        transaction.executeWithoutResult(status -> assertNoSaleWithOrderPrefix(orderId));
    }

    private static Stream<Arguments> invalidSales() {
        String orderPrefix = UUID.randomUUID().toString().substring(0, 32);
        String seller = UUID.randomUUID().toString();
        return Stream.of(
                Arguments.of("non-ASCII order ID", orderPrefix + "é", seller,
                        JpaSystemException.class, 1366, "HY000"),
                Arguments.of("non-ASCII seller", orderPrefix + "-s", seller + "é",
                        JpaSystemException.class, 1366, "HY000"),
                Arguments.of("overlength order ID", orderPrefix + "x".repeat(5), seller,
                        DataIntegrityViolationException.class, 1406, "22001"),
                Arguments.of("overlength seller", orderPrefix + "-l", "x".repeat(65),
                        DataIntegrityViolationException.class, 1406, "22001"));
    }

    @Test
    void duplicateInsertAndTimestampReplayPreserveOriginalSale() {
        String order = UUID.randomUUID().toString();
        String seller = UUID.randomUUID().toString();
        String otherSeller = UUID.randomUUID().toString();
        Instant deliveredAt = Instant.parse("2026-09-30T10:00:00Z");
        Instant refundedAt = Instant.parse("2026-10-01T10:00:00Z");
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            sellerSaleRepository.insertIfAbsent(order, seller);
            sellerSaleRepository.markDelivered(order, deliveredAt);
            sellerSaleRepository.markRefunded(order, refundedAt);
        });
        transaction.executeWithoutResult(status -> {
            sellerSaleRepository.insertIfAbsent(order, otherSeller);
            sellerSaleRepository.markDelivered(order, deliveredAt.plusSeconds(60));
            sellerSaleRepository.markRefunded(order, refundedAt.plusSeconds(60));
        });
        transaction.executeWithoutResult(status -> {
            var row = sellerSaleRepository.findById(order).orElseThrow();
            assertThat(row.getSellerSub()).isEqualTo(seller);
            assertThat(row.getDeliveredAt()).isEqualTo(deliveredAt);
            assertThat(row.getRefundedAt()).isEqualTo(refundedAt);
            assertThat(rowCount(order)).isEqualTo(1);
            assertThat(sellerSaleRepository.countCompletedSales(seller)).isZero();
            assertThat(sellerSaleRepository.countCompletedSales(otherSeller)).isZero();
        });
    }

    @Test
    void invalidCharsetFailsThroughTransactionalListenerWithoutPersistingSale() {
        String order = UUID.randomUUID().toString();
        String seller = UUID.randomUUID() + "é";
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> assertStrictSqlMode());
        MessageProperties properties = new MessageProperties();
        properties.setReceivedRoutingKey(RebyteExchanges.ORDER_DELIVERED);
        Message message = new Message(toJson(deliveredEvent(order, seller))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);

        assertThatThrownBy(() -> orderSaleListener.onOrderSale(message))
                .isInstanceOf(JpaSystemException.class)
                .rootCause().isInstanceOfSatisfying(SQLException.class, sqlError -> {
                    assertThat(sqlError.getErrorCode()).isEqualTo(1366);
                    assertThat(sqlError.getSQLState()).isEqualTo("HY000");
                });

        transaction.executeWithoutResult(status -> assertNoSaleWithOrderPrefix(order));
    }

    private void assertStrictSqlMode() {
        String sqlMode = jdbcTemplate.queryForObject("SELECT @@SESSION.sql_mode", String.class);
        assertThat(sqlMode).containsAnyOf("STRICT_TRANS_TABLES", "STRICT_ALL_TABLES");
    }

    private void assertNoSaleWithOrderPrefix(String orderId) {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seller_sale WHERE order_id LIKE ?", Integer.class,
                orderId.substring(0, 32) + "%")).isZero();
    }

    @Test
    void deliveredOrderAppearsInPublicProfileAndInternalSummary() {
        String sellerSub = UUID.randomUUID().toString();
        String orderId = UUID.randomUUID().toString();

        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent(orderId, sellerSub));

        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            ResponseEntity<Map> profile = getPublicProfile(sellerSub);
            ResponseEntity<Map> summary = restTemplate.getForEntity("/internal/users/" + sellerSub, Map.class);
            assertThat(profile.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(profile.getBody().get("completedSales")).isEqualTo(1);
            assertThat(summary.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(summary.getBody().get("completedSales")).isEqualTo(1);
        });
    }

    @Test
    void deliveryThenDeliveredRefundDoesNotCountTheSale() {
        String seller = UUID.randomUUID().toString();
        String order = UUID.randomUUID().toString();

        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent(order, seller));
        awaitOrder(order, true, false);
        publish(RebyteExchanges.ORDER_REFUNDED, refundedEvent(order, seller, "DELIVERED"));

        awaitOrder(order, true, true);
        assertThat(sellerSaleRepository.countCompletedSales(seller)).isZero();
    }

    @Test
    void refundBeforeDeliveryDoesNotCountTheSale() {
        String seller = UUID.randomUUID().toString();
        String order = UUID.randomUUID().toString();

        publish(RebyteExchanges.ORDER_REFUNDED, refundedEvent(order, seller, "DELIVERED"));
        awaitOrder(order, false, true);
        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent(order, seller));

        awaitOrder(order, true, true);
        assertThat(sellerSaleRepository.countCompletedSales(seller)).isZero();
    }

    @Test
    void repeatedDeliveryAndRefundKeepOneProjectionRow() {
        String seller = UUID.randomUUID().toString();
        String order = UUID.randomUUID().toString();
        Map<String, Object> delivered = deliveredEvent(order, seller);
        Map<String, Object> refunded = refundedEvent(order, seller, "DELIVERED");

        publish(RebyteExchanges.ORDER_DELIVERED, delivered);
        publish(RebyteExchanges.ORDER_DELIVERED, delivered);
        publish(RebyteExchanges.ORDER_REFUNDED, refunded);
        publish(RebyteExchanges.ORDER_REFUNDED, refunded);

        awaitOrder(order, true, true);
        assertThat(sellerSaleRepository.countCompletedSales(seller)).isZero();
        assertThat(rowCount(order)).isEqualTo(1);
    }

    @Test
    void repeatedDeliveryCountsOnce() {
        String seller = UUID.randomUUID().toString();
        String order = UUID.randomUUID().toString();
        Map<String, Object> delivered = deliveredEvent(order, seller);

        publish(RebyteExchanges.ORDER_DELIVERED, delivered);
        publish(RebyteExchanges.ORDER_DELIVERED, delivered);
        publish(RebyteExchanges.ORDER_DELIVERED, delivered);

        awaitOrder(order, true, false);
        assertThat(sellerSaleRepository.countCompletedSales(seller)).isEqualTo(1);
        assertThat(rowCount(order)).isEqualTo(1);
    }

    @Test
    void refundBeforeDeliveryOnlyAffectsCompletedSaleAndOnlyDeliveredStageRefunds() {
        String seller = UUID.randomUUID().toString();
        String deliveredOrder = UUID.randomUUID().toString();
        String paidOrder = UUID.randomUUID().toString();
        String shippedOrder = UUID.randomUUID().toString();

        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent(deliveredOrder, seller));
        publish(RebyteExchanges.ORDER_REFUNDED, refundedEvent(paidOrder, seller, "PAID"));
        publish(RebyteExchanges.ORDER_REFUNDED, refundedEvent(shippedOrder, seller, "SHIPPED"));
        awaitOrder(deliveredOrder, true, false);
        awaitNoQueuedSalesEvents();

        assertThat(sellerSaleRepository.countCompletedSales(seller)).isEqualTo(1);
        assertThat(rowCount(paidOrder)).isZero();
        assertThat(rowCount(shippedOrder)).isZero();
    }

    @Test
    void refundOfOneDeliveredOrderPreservesAnotherSaleByTheSameSeller() {
        String seller = UUID.randomUUID().toString();
        String refundedOrder = UUID.randomUUID().toString();
        String activeOrder = UUID.randomUUID().toString();

        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent(refundedOrder, seller));
        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent(activeOrder, seller));
        awaitOrder(refundedOrder, true, false);
        awaitOrder(activeOrder, true, false);
        publish(RebyteExchanges.ORDER_REFUNDED, refundedEvent(refundedOrder, seller, "DELIVERED"));

        awaitOrder(refundedOrder, true, true);
        assertThat(sellerSaleRepository.countCompletedSales(seller)).isEqualTo(1);
    }

    @Test
    void malformedAndUnknownMessagesAreDeadLettered() {
        publishRaw(RebyteExchanges.ORDER_DELIVERED, "not-json");
        awaitDeadLetter();

        publish(RebyteExchanges.ORDER_DELIVERED, deliveredEvent("", UUID.randomUUID().toString()));
        awaitDeadLetter();

        Map<String, Object> blankSeller = new HashMap<>(deliveredEvent(
                UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        blankSeller.put("sellerSub", " ");
        publish(RebyteExchanges.ORDER_DELIVERED, blankSeller);
        awaitDeadLetter();

        publish(RebyteExchanges.ORDER_DELIVERED,
                deliveredEvent("x".repeat(37), UUID.randomUUID().toString()));
        awaitDeadLetter();

        publish(RebyteExchanges.ORDER_DELIVERED,
                deliveredEvent(UUID.randomUUID().toString(), "x".repeat(65)));
        awaitDeadLetter();

        Map<String, Object> missingTimestamp = new HashMap<>(deliveredEvent(
                UUID.randomUUID().toString(), UUID.randomUUID().toString()));
        missingTimestamp.remove("occurredAt");
        publish(RebyteExchanges.ORDER_DELIVERED, missingTimestamp);
        awaitDeadLetter();

        publishToQueue(objectMapper.valueToTree(deliveredEvent(
                UUID.randomUUID().toString(), UUID.randomUUID().toString())).toString());
        awaitDeadLetter();
    }

    private ResponseEntity<Map> getPublicProfile(String sellerSub) {
        String token = LocalAuthTokens.mint(restTemplate, sellerSub, List.of());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange("/users/" + sellerSub, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    private Map<String, Object> deliveredEvent(String orderId, String sellerSub) {
        return Map.of(
                "eventId", UUID.randomUUID().toString(),
                "orderId", orderId,
                "listingId", UUID.randomUUID().toString(),
                "listingType", "HARDWARE",
                "buyerSub", UUID.randomUUID().toString(),
                "sellerSub", sellerSub,
                "occurredAt", Instant.now().toString());
    }

    private Map<String, Object> refundedEvent(String orderId, String sellerSub, String refundedFromStatus) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventId", UUID.randomUUID().toString());
        event.put("orderId", orderId);
        event.put("listingId", UUID.randomUUID().toString());
        event.put("listingType", "HARDWARE");
        event.put("buyerSub", UUID.randomUUID().toString());
        event.put("sellerSub", sellerSub);
        event.put("reservationId", null);
        event.put("cancellationReason", null);
        event.put("refundedFromStatus", refundedFromStatus);
        event.put("occurredAt", Instant.now().toString());
        return event;
    }

    private void publish(String routingKey, Map<String, Object> event) {
        publishRaw(routingKey, toJson(event));
    }

    private void publishRaw(String routingKey, String json) {
        rabbitTemplate.convertAndSend(RebyteExchanges.MARKETPLACE_EVENTS, routingKey, json);
    }

    private void publishToQueue(String json) {
        rabbitTemplate.convertAndSend("", RabbitConfig.ORDER_SALES_QUEUE, json);
    }

    private String toJson(Map<String, Object> event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void awaitOrder(String orderId, boolean delivered, boolean refunded) {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            var row = sellerSaleRepository.findById(orderId).orElseThrow();
            assertThat(row.getDeliveredAt() != null).isEqualTo(delivered);
            assertThat(row.getRefundedAt() != null).isEqualTo(refunded);
        });
    }

    private int rowCount(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seller_sale WHERE order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private void awaitDeadLetter() {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() ->
                assertThat(rabbitTemplate.receive(RabbitConfig.ORDER_SALES_DLQ)).isNotNull());
    }

    private void awaitNoQueuedSalesEvents() {
        await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            Integer queued = rabbitTemplate.execute(channel ->
                    channel.queueDeclarePassive(RabbitConfig.ORDER_SALES_QUEUE).getMessageCount());
            assertThat(queued).isZero();
        });
    }
}
