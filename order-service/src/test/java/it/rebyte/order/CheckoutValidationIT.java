package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import it.rebyte.order.domain.Order;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class CheckoutValidationIT extends OrderIntegrationTestBase {

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
    private OrderRepository repository;

    @AfterAll
    static void stopStub() {
        STUB.stop();
    }

    @Test
    void addressIsRequiredOnlyForHardwareAndMustBeInEuWithValidPostalCode() {
        String listingId = register("HARDWARE", "12.37", "4.19", "seller-" + UUID.randomUUID());
        String buyer = "buyer-" + UUID.randomUUID();

        assertThat(checkout(buyer, request(listingId, "16.56", null)).getStatusCode().value()).isEqualTo(422);
        assertThat(checkout(buyer, request(listingId, "16.56", address("US", "10001")))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(checkout(buyer, request(listingId, "16.56", address("IT", "12A45")))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(checkout(buyer, request(listingId, "16.56", address("NL", "1234 AB")))
                .getStatusCode()).isEqualTo(HttpStatus.CREATED);

        String softwareId = register("SOFTWARE", "12.37", "0.00", "seller-" + UUID.randomUUID());
        assertThat(checkout(buyer, request(softwareId, "12.37", address("IT", "20100")))
                .getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void exactQuoteAndAmountScaleAreEnforcedBeforePaymentCreate() {
        String listingId = register("HARDWARE", "12.37", "4.19", "seller-" + UUID.randomUUID());
        String buyer = "buyer-" + UUID.randomUUID();
        int calls = STUB.paymentCalls().size();

        ResponseEntity<Map> stale = checkout(buyer, request(listingId, "16.55", address("IT", "20100")));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(new BigDecimal(String.valueOf(stale.getBody().get("currentTotal"))))
                .isEqualByComparingTo("16.56");
        assertThat(checkout(buyer, request(listingId, "16.560", address("IT", "20100")))
                .getStatusCode().value()).isEqualTo(422);
        assertThat(STUB.paymentCalls()).hasSize(calls);

        ResponseEntity<Map> exact = checkout(buyer, request(listingId, "16.56", address("IT", "20100")));
        assertThat(exact.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(STUB.paymentCalls()).hasSize(calls + 1);
    }

    @Test
    void selfPurchaseIsRefusedBeforeGuardOrPayment() {
        String seller = "seller-" + UUID.randomUUID();
        String listingId = register("HARDWARE", "12.37", "4.19", seller);
        int calls = STUB.paymentCalls().size();

        assertThat(checkout(seller, request(listingId, "16.56", address("IT", "20100")))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(repository.findAll().stream().noneMatch(order -> listingId.equals(order.getListingId()))).isTrue();
        assertThat(STUB.paymentCalls()).hasSize(calls);
    }

    @Test
    void paymentUnavailableCancelsAndReleasesHardwareGuard() {
        String listingId = register("HARDWARE", "12.37", "4.19", "seller-" + UUID.randomUUID());
        STUB.setPaymentUnavailable(true);
        try {
            ResponseEntity<Map> response = checkout("buyer-" + UUID.randomUUID(),
                    request(listingId, "16.56", address("IT", "20100")));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            Order order = repository.findAll().stream()
                    .filter(candidate -> listingId.equals(candidate.getListingId())).findFirst().orElseThrow();
            assertThat(order.getActiveListingGuard()).isNull();
            assertThat(order.getCheckoutPhase()).isEqualTo(CheckoutPhase.CANCELLED);
            assertThat(order.getCancellationReason()).isEqualTo(it.rebyte.order.domain.CancellationReason.PAYMENT_UNAVAILABLE);
        } finally {
            STUB.setPaymentUnavailable(false);
        }
    }

    @Test
    void unknownListingIsUnavailable() {
        ResponseEntity<Map> response = checkout("buyer-" + UUID.randomUUID(),
                request(UUID.randomUUID().toString(), "16.56", address("IT", "20100")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("listing-unavailable");
    }

    @Test
    void stuckViewListsFailedCreateWithoutPayment() {
        String listingId = register("HARDWARE", "12.37", "4.19", "seller-" + UUID.randomUUID());
        STUB.setPaymentUnavailable(true);
        try {
            checkout("buyer-" + UUID.randomUUID(), request(listingId, "16.56", address("IT", "20100")));
        } finally {
            STUB.setPaymentUnavailable(false);
        }
        String orderId = repository.findAll().stream()
                .filter(candidate -> listingId.equals(candidate.getListingId())).findFirst().orElseThrow().getId();

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token("admin-operator"));
        ResponseEntity<Map> response = restTemplate.exchange("/admin/orders/stuck?size=50", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) response.getBody().get("items");
        assertThat(items).filteredOn(item -> orderId.equals(item.get("orderId")))
                .extracting(item -> item.get("category")).containsExactly("failed_create");
    }

    @Test
    void paymentCreateSlowerThanOneSecondButWithinReadTimeoutStillAwaitsPayment() {
        // 1500ms: above the 1s TimeLimiter default, below the payment read timeout.
        assertSlowPaymentCreateStillAwaitsPayment(1500);
    }

    @Test
    void coldPaymentCreateWithPayPalTokenFetchStillAwaitsPayment() {
        // 2500ms: a first PayPal create also fetches an OAuth token; observed at about 2.6s.
        assertSlowPaymentCreateStillAwaitsPayment(2500);
    }

    private void assertSlowPaymentCreateStillAwaitsPayment(long delayMillis) {
        String listingId = register("HARDWARE", "12.37", "4.19", "seller-" + UUID.randomUUID());
        int calls = STUB.paymentCalls().size();
        STUB.setPaymentDelayMillis(delayMillis);
        try {
            long start = System.nanoTime();
            ResponseEntity<Map> response = checkout("buyer-" + UUID.randomUUID(),
                    request(listingId, "16.56", address("IT", "20100")));
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            assertThat(response.getBody().get("status")).isEqualTo("AWAITING_PAYMENT");
            assertThat(String.valueOf(response.getBody().get("approvalUrl"))).isNotBlank();
            assertThat(elapsedMillis).isGreaterThanOrEqualTo(delayMillis);
            assertThat(STUB.paymentCalls()).hasSize(calls + 1);

            Order order = repository.findAll().stream()
                    .filter(candidate -> listingId.equals(candidate.getListingId())).findFirst().orElseThrow();
            assertThat(order.getStatus()).isEqualTo(it.rebyte.order.domain.OrderStatus.AWAITING_PAYMENT);
            assertThat(order.getCheckoutPhase()).isEqualTo(CheckoutPhase.READY);
            assertThat(order.getActiveListingGuard()).isEqualTo(listingId);
            assertThat(order.getCancellationReason()).isNull();
        } finally {
            STUB.setPaymentDelayMillis(0);
        }
    }

    private String register(String type, String price, String shipping, String seller) {
        String listingId = UUID.randomUUID().toString();
        STUB.registerListing(listingId, seller, "Hardware or software", new BigDecimal(price),
                new BigDecimal(shipping), type, List.of("PAYPAL", "CREDIT_CARD"), true, 1L);
        return listingId;
    }

    private Map<String, Object> address(String country, String postalCode) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("recipient", "Mario Rossi");
        value.put("street", "Via Roma 1");
        value.put("city", "Milano");
        value.put("postalCode", postalCode);
        value.put("country", country);
        return value;
    }

    private Map<String, Object> request(String listingId, String expectedTotal, Map<String, Object> address) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", "PAYPAL");
        body.put("expectedTotal", expectedTotal);
        if (address != null) {
            body.put("address", address);
        }
        return body;
    }

    private ResponseEntity<Map> checkout(String buyer, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(buyer));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", "idem-" + UUID.randomUUID());
        return restTemplate.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
