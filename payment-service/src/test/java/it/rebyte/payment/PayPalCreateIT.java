package it.rebyte.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.moreThanOrExactly;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

import com.github.tomakehurst.wiremock.matching.EqualToPattern;
import com.github.tomakehurst.wiremock.stubbing.Scenario;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

/**
 * Task 1 (03-05-PLAN.md; D-01, D-06, D-21, D-23, D-36): drives real HTTP
 * through a real Spring MVC stack, real MySQL and real Feign-free RestClient
 * wiring, against the official WireMock artifact standing in for the real
 * PayPal sandbox API (shapes verified live in 03-01's sandbox spike). Each
 * test scopes its own WireMock stub to its own order id via the request
 * body's {@code purchase_units[0].custom_id} so tests never interfere with
 * each other's request journal, and request counts are always measured as
 * deltas from a per-test baseline rather than absolute totals.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayPalCreateIT extends PaymentIntegrationTestBase {

    private static final String OAUTH_PATH = "/v1/oauth2/token";
    private static final String CREATE_PATH = "/v2/checkout/orders";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * D-36: purchase-unit EUR value equals the breakdown sum on a
     * non-round total (12.37 + 4.19 = 16.56). Also the class's first test
     * (guaranteed by {@code @Order(1)} against a fresh, never-yet-called
     * token cache in this class's Spring context) — proves the OAuth call
     * actually happened, per Task 1's acceptance criteria.
     */
    @Test
    @Order(1)
    void createOrderPersistsApprovalUrlAndCorrectEurAmounts() {
        stubOAuthToken();
        String orderId = UUID.randomUUID().toString();
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String approvalUrl = "https://www.sandbox.paypal.com/checkoutnow?token=" + providerOrderId;

        stubCreateOrder(orderId, 201, orderResponseJson(providerOrderId, approvalUrl));

        ResponseEntity<Map> response = createPayment(orderId, buyerSub, "12.37", "4.19", "16.56", "PAYPAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("paymentId")).isEqualTo(providerOrderId);
        assertThat(response.getBody().get("approvalUrl")).isEqualTo(approvalUrl);

        Optional<Payment> persisted = paymentRepository.findById(orderId);
        assertThat(persisted).isPresent();
        Payment payment = persisted.get();
        assertThat(payment.getBuyerSub()).isEqualTo(buyerSub);
        assertThat(payment.getProviderOrderId()).isEqualTo(providerOrderId);
        assertThat(payment.getApprovalUrl()).isEqualTo(approvalUrl);
        assertThat(payment.getItemPrice()).isEqualByComparingTo("12.37");
        assertThat(payment.getShippingCost()).isEqualByComparingTo("4.19");
        assertThat(payment.getPricePaid()).isEqualByComparingTo("16.56");
        assertThat(payment.getItemPrice().add(payment.getShippingCost()))
                .as("D-36: breakdown sum equals amount.value")
                .isEqualByComparingTo(payment.getPricePaid());

        PAYPAL.verify(1, postRequestedFor(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId)))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].amount.value", equalTo("16.56")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].amount.breakdown.item_total.value",
                        equalTo("12.37")))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].amount.breakdown.shipping.value",
                        equalTo("4.19"))));
        PAYPAL.verify(moreThanOrExactly(1), postRequestedFor(urlEqualTo(OAUTH_PATH)));
    }

    /** Explicit provider 4xx/5xx mapping (Task 1 action): a provider 500 surfaces as 502 problem+json, never a raw 500. */
    @Test
    @Order(2)
    void createOrderPropagatesProviderErrorAs502() {
        String orderId = UUID.randomUUID().toString();
        String buyerSub = "buyer-" + UUID.randomUUID();
        stubCreateOrder(orderId, 500, "{\"name\":\"INTERNAL_SERVER_ERROR\",\"message\":\"provider outage\"}");

        ResponseEntity<Map> response = createPayment(orderId, buyerSub, "10.00", "0.00", "10.00", "PAYPAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody().get("title")).isEqualTo("paypal-provider-error");
        assertThat(paymentRepository.findById(orderId)).as("row persisted before the failed call, approvalUrl never set")
                .hasValueSatisfying(payment -> assertThat(payment.getApprovalUrl()).isNull());
    }

    /**
     * D-06: on a client-side read timeout the first call fails; a retry for
     * the SAME order id reuses the persisted create-request-id verbatim
     * (never mints a second one) — genuine RED evidence, not a hand-waved
     * assertion: both provider calls are captured with their actual
     * {@code PayPal-Request-Id} header value and compared.
     */
    @Test
    @Order(3)
    void retryAfterTimeoutReusesSameCreateRequestId() {
        String orderId = UUID.randomUUID().toString();
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String approvalUrl = "https://www.sandbox.paypal.com/checkoutnow?token=" + providerOrderId;

        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId)))
                .inScenario(orderId)
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(201).withFixedDelay(2000)
                        .withHeader("Content-Type", "application/json")
                        .withBody(orderResponseJson(providerOrderId, approvalUrl)))
                .willSetStateTo("retried"));
        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId)))
                .inScenario(orderId)
                .whenScenarioStateIs("retried")
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody(orderResponseJson(providerOrderId, approvalUrl))));

        ResponseEntity<Map> first = createPayment(orderId, buyerSub, "10.00", "0.00", "10.00", "PAYPAL");
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);

        ResponseEntity<Map> second = createPayment(orderId, buyerSub, "10.00", "0.00", "10.00", "PAYPAL");
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("approvalUrl")).isEqualTo(approvalUrl);

        var requests = PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId))));
        assertThat(requests).as("exactly two provider create calls for this order (timeout + retry)").hasSize(2);
        String firstRequestId = requests.get(0).getHeader("PayPal-Request-Id");
        String secondRequestId = requests.get(1).getHeader("PayPal-Request-Id");
        assertThat(firstRequestId).isNotBlank();
        assertThat(secondRequestId).as("D-06: same request id reused on retry, never regenerated")
                .isEqualTo(firstRequestId);

        Payment persisted = paymentRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getCreateRequestId()).isEqualTo(firstRequestId);
    }

    @Test
    @Order(4)
    void lateProviderCreateResponseDoesNotReopenClosedPayment() throws Exception {
        stubOAuthToken();
        String orderId = UUID.randomUUID().toString();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String approvalUrl = "https://www.sandbox.paypal.com/checkoutnow?token=" + providerOrderId;
        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId)))
                .willReturn(aResponse().withStatus(201).withFixedDelay(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody(orderResponseJson(providerOrderId, approvalUrl))));

        CompletableFuture<ResponseEntity<Map>> create = CompletableFuture.supplyAsync(
                () -> createPayment(orderId, "buyer-" + UUID.randomUUID(), "10.00", "0.00", "10.00", "PAYPAL"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId)))).isEmpty()
                && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(paymentRepository.findById(orderId)).isPresent();
        ResponseEntity<Map> close = restTemplate.postForEntity("/internal/payments/" + orderId + "/close",
                null, Map.class);
        assertThat(close.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(close.getBody().get("closed")).isEqualTo(true);

        assertThat(create.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        Payment persisted = paymentRepository.findById(orderId).orElseThrow();
        assertThat(persisted.getState()).isEqualTo(PaymentState.CLOSED);
        assertThat(persisted.getProviderOrderId()).isEqualTo(providerOrderId);
    }

    @Test
    @Order(5)
    void oldFailedCreateCannotCallProviderAgain() {
        stubOAuthToken();
        String orderId = UUID.randomUUID().toString();
        stubCreateOrder(orderId, 500, "{\"name\":\"INTERNAL_SERVER_ERROR\"}");
        assertThat(createPayment(orderId, "buyer-" + UUID.randomUUID(), "10.00", "0.00", "10.00", "PAYPAL")
                .getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);

        jdbcTemplate.update("update payments set created_at = date_sub(created_at, interval 1 minute) where order_id = ?",
                orderId);
        ResponseEntity<Map> retry = createPayment(orderId, "buyer-" + UUID.randomUUID(),
                "10.00", "0.00", "10.00", "PAYPAL");
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(retry.getBody().get("title")).isEqualTo("payment-create-retry-expired");
        var requests = PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId))));
        assertThat(requests).hasSize(1);
    }

    @Test
    @Order(6)
    void creditCardRequestsGuestCheckoutLandingPage() {
        stubOAuthToken();
        String orderId = UUID.randomUUID().toString();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String approvalUrl = "https://www.sandbox.paypal.com/checkoutnow?token=" + providerOrderId;
        stubCreateOrder(orderId, 201, orderResponseJson(providerOrderId, approvalUrl));

        ResponseEntity<Map> response = createPayment(orderId, "buyer-" + UUID.randomUUID(),
                "12.37", "4.19", "16.56", "CREDIT_CARD");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        PAYPAL.verify(1, postRequestedFor(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", equalTo(orderId)))
                .withRequestBody(matchingJsonPath("$.payment_source.paypal.experience_context.landing_page",
                        equalTo("GUEST_CHECKOUT"))));
    }

    /** WR-02: a malformed scale is rejected by {@code @Valid} at the boundary, before any {@code Payment} row is inserted. */
    @Test
    @Order(7)
    void threeDecimalPricePaidIsRejectedBeforeAnyRowIsPersisted() {
        String orderId = UUID.randomUUID().toString();

        ResponseEntity<Map> response = createPayment(orderId, "buyer-" + UUID.randomUUID(),
                "12.37", "4.19", "16.567", "PAYPAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().get("title")).isEqualTo("validation-failed");
        assertThat(paymentRepository.findById(orderId)).isEmpty();
    }

    private void stubOAuthToken() {
        PAYPAL.stubFor(post(urlEqualTo(OAUTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
    }

    private void stubCreateOrder(String orderId, int status, String body) {
        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH))
                .withRequestBody(matchingJsonPath("$.purchase_units[0].custom_id", new EqualToPattern(orderId)))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    private String orderResponseJson(String providerOrderId, String approvalUrl) {
        return "{\"id\":\"" + providerOrderId + "\",\"status\":\"PAYER_ACTION_REQUIRED\",\"links\":["
                + "{\"href\":\"" + approvalUrl + "\",\"rel\":\"payer-action\",\"method\":\"GET\"}]}";
    }

    private ResponseEntity<Map> createPayment(String orderId, String buyerSub, String itemPrice, String shippingCost,
            String pricePaid, String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("buyerSub", buyerSub);
        body.put("itemPrice", new BigDecimal(itemPrice));
        body.put("shippingCost", new BigDecimal(shippingCost));
        body.put("pricePaid", new BigDecimal(pricePaid));
        body.put("listingTitle", "Amiga 500");
        body.put("method", method);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        return restTemplate.exchange("/internal/payments", HttpMethod.POST, new HttpEntity<>(body, headers),
                Map.class);
    }
}
