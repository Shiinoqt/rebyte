package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

/**
 * Task 2 (03-04-PLAN.md; D-16, D-17, D-19): N distinct buyers, barrier
 * started so every {@code POST /orders} truly overlaps, racing the same
 * hardware listing's unique {@code active_listing_guard} against real
 * MySQL. Catalog and payment are stubbed exactly like {@code
 * HardwareCheckoutTracerIT} — the mechanism under test is the MySQL guard
 * itself (D-18: no Redis lock is involved anywhere in this flow), not
 * catalog's own reserve fencing (already proven live by 03-02's
 * InternalReservationContractIT / LateReservationFenceIT).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class HardwareCheckoutRaceIT extends OrderIntegrationTestBase {

    private static final int CONCURRENT_BUYERS = 8;

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
    void nBarrierStartedBuyersYieldOneOrderAndLosingRequestsNeverCallPaymentCreate() throws Exception {
        String listingId = UUID.randomUUID().toString();
        String sellerSub = "seller-" + UUID.randomUUID();
        STUB.registerListing(listingId, sellerSub, "ThinkPad X220", new BigDecimal("180.00"), new BigDecimal("9.00"),
                "HARDWARE", List.of("PAYPAL"), true, 1L);

        CyclicBarrier barrier = new CyclicBarrier(CONCURRENT_BUYERS);
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT_BUYERS);
        try {
            List<CompletableFuture<ResponseEntity<Map>>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_BUYERS; i++) {
                String buyerSub = "buyer-" + i + "-" + UUID.randomUUID();
                String idempotencyKey = "idem-" + i + "-" + UUID.randomUUID();
                futures.add(CompletableFuture.supplyAsync(() -> {
                    awaitBarrier(barrier);
                    return checkout(buyerSub, listingId, idempotencyKey);
                }, pool));
            }
            List<ResponseEntity<Map>> responses = futures.stream().map(CompletableFuture::join).toList();

            long created = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CREATED).count();
            long conflicted = responses.stream().filter(r -> r.getStatusCode() == HttpStatus.CONFLICT).count();

            assertThat(created).as("exactly one buyer wins the unique active_listing_guard").isEqualTo(1);
            assertThat(conflicted).as("every losing buyer gets 409").isEqualTo(CONCURRENT_BUYERS - 1);
            assertThat(created + conflicted).as("no response is anything other than 201 or 409")
                    .isEqualTo(CONCURRENT_BUYERS);

            long persistedOrders = orderRepository.findAll().stream()
                    .filter(order -> listingId.equals(order.getListingId()))
                    .count();
            assertThat(persistedOrders).as("only the winner's order row exists").isEqualTo(1);

            // D-16: no MySQL transaction is ever held across the Feign call —
            // the guard alone arbitrates, so losers never reach catalog/payment.
            assertThat(STUB.reserveCalls()).as("only the winning buyer's request ever calls catalog reserve")
                    .hasSize(1);
            assertThat(STUB.paymentCalls()).as("losing requests never call payment create")
                    .hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    private void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception e) {
            throw new IllegalStateException("barrier wait failed", e);
        }
    }

    private ResponseEntity<Map> checkout(String buyerSub, String listingId, String idempotencyKey) {
        String token = token(buyerSub);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", "PAYPAL");
        body.put("expectedTotal", "189.00");
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
