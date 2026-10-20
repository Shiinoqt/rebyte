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
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class SoftwareCheckoutIT extends OrderIntegrationTestBase {

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
    void softwarePurchaseSkipsReservationAndReplaysSameKeyWithoutAnotherPayment() {
        String listingId = registerSoftware();
        String buyer = "buyer-" + UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();
        int paymentCallsBefore = STUB.paymentCalls().size();
        int reserveCallsBefore = STUB.reserveCalls().size();

        ResponseEntity<Map> first = checkout(buyer, key, request(listingId, "PAYPAL", "16.56"));
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String orderId = String.valueOf(first.getBody().get("orderId"));
        Order order = repository.findById(orderId).orElseThrow();
        assertThat(order.getListingType()).isEqualTo(ListingType.SOFTWARE);
        assertThat(order.getShippingCost()).isEqualByComparingTo("0.00");
        assertThat(order.getActiveListingGuard()).isNull();
        assertThat(order.getReservationId()).isNull();
        assertThat(order.getCheckoutPhase()).isEqualTo(CheckoutPhase.READY);
        assertThat(STUB.reserveCalls()).hasSize(reserveCallsBefore);

        ResponseEntity<Map> replay = checkout(buyer, key, request(listingId, "PAYPAL", "16.56"));
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(replay.getBody().get("orderId")).isEqualTo(orderId);
        assertThat(replay.getBody().get("approvalUrl")).isEqualTo(first.getBody().get("approvalUrl"));
        assertThat(STUB.paymentCalls()).hasSize(paymentCallsBefore + 1);

        ResponseEntity<Map> anotherPurchase = checkout(buyer, "idem-" + UUID.randomUUID(),
                request(listingId, "PAYPAL", "16.56"));
        assertThat(anotherPurchase.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(anotherPurchase.getBody().get("orderId")).isNotEqualTo(orderId);
        assertThat(STUB.paymentCalls()).hasSize(paymentCallsBefore + 2);
    }

    @Test
    void changedPayloadWithSameKeyConflicts() {
        String listingId = registerSoftware();
        String buyer = "buyer-" + UUID.randomUUID();
        String key = "idem-" + UUID.randomUUID();
        assertThat(checkout(buyer, key, request(listingId, "PAYPAL", "16.56")).getStatusCode())
                .isEqualTo(HttpStatus.CREATED);

        ResponseEntity<Map> changed = checkout(buyer, key, request(listingId, "CREDIT_CARD", "16.56"));
        assertThat(changed.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(changed.getBody().get("title")).isEqualTo("idempotency-key-conflict");
    }

    @Test
    void bankTransferAndUnknownOfferAreRejected() {
        String listingId = registerSoftware();
        String buyer = "buyer-" + UUID.randomUUID();
        ResponseEntity<Map> bank = checkout(buyer, "idem-" + UUID.randomUUID(),
                request(listingId, "BANK_TRANSFER", "16.56"));
        assertThat(bank.getStatusCode().value()).isEqualTo(422);

        Map<String, Object> offered = request(listingId, "PAYPAL", "16.56");
        offered.put("offerId", UUID.randomUUID().toString());
        ResponseEntity<Map> offer = checkout(buyer, "idem-" + UUID.randomUUID(), offered);
        assertThat(offer.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(offer.getBody().get("title")).isEqualTo("offer-not-found");
    }

    private String registerSoftware() {
        String listingId = UUID.randomUUID().toString();
        STUB.registerListing(listingId, "seller-" + UUID.randomUUID(), "Amiga utility",
                new BigDecimal("16.56"), new BigDecimal("0.00"), "SOFTWARE",
                List.of("PAYPAL", "CREDIT_CARD", "BANK_TRANSFER"), true, 1L);
        return listingId;
    }

    private Map<String, Object> request(String listingId, String method, String expectedTotal) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("listingId", listingId);
        body.put("method", method);
        body.put("expectedTotal", expectedTotal);
        return body;
    }

    private ResponseEntity<Map> checkout(String buyer, String key, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(buyer));
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        return restTemplate.exchange("/orders", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }
}
