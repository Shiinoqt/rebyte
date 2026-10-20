package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * Task 1 (03-04-PLAN.md; D-01, D-09, D-13, D-16, D-17, D-18): drives real
 * HTTP through a real Spring MVC stack, real MySQL and real Feign wiring,
 * against stub HTTP servers standing in for catalog-service and
 * payment-service (payment-service does not exist yet — it ships in
 * 03-05). Proves the end-to-end sequence: fresh catalog read -> guard row
 * committed in its own transaction (D-16) -> catalog reserve with
 * {@code orderId} as the caller-supplied {@code reservationId} (D-17) ->
 * synchronous payment create (D-01) -> {@code approvalUrl} returned; and
 * that a second buyer racing the same hardware listing after the first
 * gets 409 without the MySQL guard ever letting them reach catalog.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class HardwareCheckoutTracerIT extends OrderIntegrationTestBase {

    private static final CheckoutStub STUB = new CheckoutStub();

    @DynamicPropertySource
    static void stubUrls(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.openfeign.client.config.catalog-service.url",
                () -> "http://localhost:" + STUB.port());
        registry.add("spring.cloud.openfeign.client.config.payment-service.url",
                () -> "http://localhost:" + STUB.port());
    }

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private OrderRepository orderRepository;

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void checkoutCreatesOrderPersistsSnapshotReservesListingAndReturnsApprovalUrl() {
        String listingId = UUID.randomUUID().toString();
        String sellerSub = "seller-" + UUID.randomUUID();
        String buyerSub = "buyer-" + UUID.randomUUID();
        STUB.registerListing(listingId, sellerSub, "Amiga 500", new BigDecimal("450.00"), new BigDecimal("12.50"),
                "HARDWARE", List.of("PAYPAL", "CREDIT_CARD"), true, 1L);

        ResponseEntity<Map> response = checkout(buyerSub, listingId, "idem-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        Map body = response.getBody();
        String orderId = (String) body.get("orderId");
        assertThat(orderId).isNotBlank();
        assertThat(body.get("approvalUrl")).isEqualTo("https://paypal.sandbox.example/approve/" + orderId);
        assertThat(body.get("status")).isEqualTo("AWAITING_PAYMENT");

        Optional<Order> persisted = orderRepository.findById(orderId);
        assertThat(persisted).isPresent();
        Order order = persisted.get();
        assertThat(order.getBuyerSub()).isEqualTo(buyerSub);
        assertThat(order.getSellerSub()).isEqualTo(sellerSub);
        assertThat(order.getListingId()).isEqualTo(listingId);
        assertThat(order.getListingType()).isEqualTo(ListingType.HARDWARE);
        assertThat(order.getItemPrice()).isEqualByComparingTo("450.00");
        assertThat(order.getShippingCost()).isEqualByComparingTo("12.50");
        assertThat(order.getPricePaid()).isEqualByComparingTo("462.50");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        assertThat(order.getCheckoutPhase()).isEqualTo(CheckoutPhase.READY);
        assertThat(order.getActiveListingGuard()).isEqualTo(listingId);
        // D-17: orderId IS the reservationId sent to (and echoed back by) catalog reserve.
        assertThat(order.getReservationId()).isEqualTo(orderId);
        assertThat(order.getApprovalUrl()).isEqualTo(body.get("approvalUrl"));

        assertThat(STUB.reserveCalls()).as("catalog reserve called exactly once, with orderId as reservationId")
                .hasSize(1)
                .first()
                .satisfies(call -> {
                    assertThat(call.get("listingId")).isEqualTo(listingId);
                    assertThat(call.get("reservationId")).isEqualTo(orderId);
                    assertThat(String.valueOf(call.get("expectedVersion"))).isEqualTo("1");
                });

        assertThat(STUB.paymentCalls()).as("payment create called exactly once, buyerSub from the authenticated snapshot")
                .hasSize(1)
                .first()
                .satisfies(call -> {
                    assertThat(call.get("orderId")).isEqualTo(orderId);
                    assertThat(call.get("buyerSub")).isEqualTo(buyerSub);
                });
    }

    @Test
    void secondBuyerForSameListingGetsConflictAndOnlyOneGuardSurvives() {
        String listingId = UUID.randomUUID().toString();
        String sellerSub = "seller-" + UUID.randomUUID();
        STUB.registerListing(listingId, sellerSub, "ThinkPad T420", new BigDecimal("180.00"), new BigDecimal("9.00"),
                "HARDWARE", List.of("PAYPAL"), true, 1L);

        String firstBuyer = "buyer-a-" + UUID.randomUUID();
        ResponseEntity<Map> first = checkout(firstBuyer, listingId, "idem-a-" + UUID.randomUUID(), "189.00");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String firstOrderId = (String) first.getBody().get("orderId");

        int reserveCallsAfterFirst = STUB.reserveCalls().size();

        String secondBuyer = "buyer-b-" + UUID.randomUUID();
        ResponseEntity<Map> second = checkout(secondBuyer, listingId, "idem-b-" + UUID.randomUUID(), "189.00");

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(second.getBody().get("title")).isEqualTo("listing-unavailable");

        long ordersForListing = orderRepository.findAll().stream()
                .filter(order -> listingId.equals(order.getListingId()))
                .count();
        assertThat(ordersForListing).as("only the winner's order row exists").isEqualTo(1);

        Order winner = orderRepository.findById(firstOrderId).orElseThrow();
        assertThat(winner.getActiveListingGuard()).isEqualTo(listingId);

        // D-16: the loser never reaches catalog — the unique guard fails inside
        // MySQL before any Feign call, no MySQL transaction held across it.
        assertThat(STUB.reserveCalls()).as("the loser's request never calls catalog reserve")
                .hasSize(reserveCallsAfterFirst);
    }

    private ResponseEntity<Map> checkout(String buyerSub, String listingId, String idempotencyKey) {
        return checkout(buyerSub, listingId, idempotencyKey, "462.50");
    }

    private ResponseEntity<Map> checkout(String buyerSub, String listingId, String idempotencyKey,
            String expectedTotal) {
        String token = token(buyerSub);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", "PAYPAL");
        body.put("expectedTotal", expectedTotal);
        Map<String, Object> address = new LinkedHashMap<>();
        address.put("recipient", "Mario Rossi");
        address.put("street", "Via Roma 1");
        address.put("city", "Milano");
        address.put("postalCode", "20100");
        address.put("country", "IT");
        body.put("address", address);

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", idempotencyKey);

        return restTemplate.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
