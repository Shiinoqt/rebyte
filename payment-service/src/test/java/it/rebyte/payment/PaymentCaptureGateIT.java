package it.rebyte.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

import com.github.tomakehurst.wiremock.client.WireMock;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

/**
 * Task 2 (03-05-PLAN.md; D-02, transaction recommendation 5): drives real
 * HTTP through a real Spring MVC stack, real MySQL, real Feign wiring to
 * order-service's capture-authorization grant, and the official WireMock
 * artifact standing in for PayPal's capture call. Every test creates its
 * own Payment row via the real {@code POST /internal/payments} path (Task
 * 1), so the capture gate is always exercised against a genuinely persisted
 * row, never a hand-inserted fixture.
 *
 * <p>WireMock resets its stubs and request journal before every test
 * method by default, so tests never interfere with each other's counts —
 * no manual {@code resetAll()} needed (confirmed empirically: this class
 * shares its two static WireMock instances with {@code PayPalCreateIT} via
 * the common base class, exactly like order-service's two *IT classes share
 * one MySQL container).
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
class PaymentCaptureGateIT extends PaymentIntegrationTestBase {

    private static final String OAUTH_PATH = "/v1/oauth2/token";
    private static final String CREATE_PATH = "/v2/checkout/orders";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository paymentRepository;

    @Test
    void wrongBuyerRejectedSendsZeroGrantAndZeroCaptureCalls() {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String orderId = createOpenPayment(buyerSub, "PPO-" + UUID.randomUUID());

        ResponseEntity<Map> response = capture(orderId, "not-" + buyerSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().get("title")).isEqualTo("wrong-buyer");
        assertThat(ORDER_SERVICE.findAll(postRequestedFor(WireMock.urlPathMatching("/internal/orders/.*"))))
                .as("wrong buyer never reaches the order-service grant call").isEmpty();
        assertThat(PAYPAL.findAll(postRequestedFor(WireMock.urlPathMatching(".*/capture$"))))
                .as("wrong buyer never reaches a PayPal capture call").isEmpty();
        assertThat(paymentRepository.findById(orderId).orElseThrow().getState()).isEqualTo(PaymentState.OPEN);
    }

    @Test
    void absentPaymentRowReturns404() {
        String orderId = UUID.randomUUID().toString();
        ResponseEntity<Map> response = capture(orderId, "buyer-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("title")).isEqualTo("payment-not-found");
    }

    @Test
    void captureDeniedByOrderServiceSendsZeroPayPalCaptureCalls() {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = createOpenPayment(buyerSub, providerOrderId);
        stubGrant(orderId, "{\"outcome\":\"DENIED\",\"checkoutPhase\":\"CREATING\"}");

        ResponseEntity<Map> response = capture(orderId, buyerSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("capture-denied");
        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH + "/" + providerOrderId + "/capture"))))
                .as("order-service denied the grant: zero PayPal capture calls").isEmpty();
        // Claimed locally (CAPTURING) even though the grant was denied — a
        // visible, reconcilable "stuck" claim per D-26's philosophy, not a
        // silent revert of the claim.
        assertThat(paymentRepository.findById(orderId).orElseThrow().getState()).isEqualTo(PaymentState.CAPTURING);
    }

    @Test
    void orderServiceTimeoutSendsZeroPayPalCaptureCalls() {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = createOpenPayment(buyerSub, providerOrderId);
        // application.yml: order-service Feign client read-timeout is 2000ms;
        // a 5s fixed delay reliably triggers a genuine client-side timeout.
        ORDER_SERVICE.stubFor(post(urlEqualTo("/internal/orders/" + orderId + "/capture-authorizations"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"outcome\":\"GRANTED\",\"checkoutPhase\":\"CAPTURE_AUTHORIZED\"}")));

        ResponseEntity<Map> response = capture(orderId, buyerSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("capture-denied");
        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH + "/" + providerOrderId + "/capture"))))
                .as("order-service timed out: zero PayPal capture calls").isEmpty();
    }

    @Test
    void closeBeforeCaptureClaimPreventsCapture() {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String orderId = createOpenPayment(buyerSub, "PPO-" + UUID.randomUUID());

        ResponseEntity<Map> closeResponse = restTemplate.postForEntity("/internal/payments/" + orderId + "/close",
                null, Map.class);
        assertThat(closeResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(closeResponse.getBody().get("closed")).isEqualTo(true);
        assertThat(paymentRepository.findById(orderId).orElseThrow().getState()).isEqualTo(PaymentState.CLOSED);

        ResponseEntity<Map> response = capture(orderId, buyerSub);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("payment-closed");
        assertThat(ORDER_SERVICE.findAll(postRequestedFor(WireMock.urlPathMatching("/internal/orders/.*"))))
                .as("closed before the claim: capture never even requests the grant").isEmpty();
    }

    /**
     * D-02, PAY-02's core correctness property: granted capture calls
     * PayPal exactly once, and a repeated request for the same order (a
     * buyer re-clicking, or a process-restart retry) reuses the SAME
     * operation id and captures PayPal a SECOND time never — it just
     * returns the same accepted response.
     */
    @Test
    void captureGrantedCallsPayPalOnceAndRetryNeverChargesTwice() {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = createOpenPayment(buyerSub, providerOrderId);
        stubGrant(orderId, "{\"outcome\":\"GRANTED\",\"checkoutPhase\":\"CAPTURE_AUTHORIZED\"}");
        String captureId = "CAP-" + UUID.randomUUID();
        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH + "/" + providerOrderId + "/capture"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + providerOrderId + "\",\"status\":\"COMPLETED\","
                                + "\"purchase_units\":[{\"payments\":{\"captures\":[{\"id\":\"" + captureId
                                + "\",\"status\":\"COMPLETED\"}]}}]}")));

        ResponseEntity<Map> first = capture(orderId, buyerSub);
        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody().get("status")).isEqualTo("ACCEPTED_AWAITING_CONFIRMATION");
        assertThat(first.getBody().get("orderId")).isEqualTo(orderId);

        Payment afterFirst = paymentRepository.findById(orderId).orElseThrow();
        assertThat(afterFirst.getState()).isEqualTo(PaymentState.CAPTURING);
        assertThat(afterFirst.getProviderCaptureId()).isEqualTo(captureId);
        String operationIdAfterFirst = afterFirst.getCaptureOperationId();
        String captureRequestIdAfterFirst = afterFirst.getCaptureRequestId();

        // Same buyer retries (double click / process-restart retry).
        ResponseEntity<Map> second = capture(orderId, buyerSub);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody().get("status")).isEqualTo("ACCEPTED_AWAITING_CONFIRMATION");

        Payment afterSecond = paymentRepository.findById(orderId).orElseThrow();
        assertThat(afterSecond.getCaptureOperationId()).as("at most one capture operation id per order")
                .isEqualTo(operationIdAfterFirst);
        assertThat(afterSecond.getCaptureRequestId()).as("at most one capture request id per order")
                .isEqualTo(captureRequestIdAfterFirst);
        assertThat(afterSecond.getProviderCaptureId()).isEqualTo(captureId);

        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH + "/" + providerOrderId + "/capture"))))
                .as("PayPal capture called exactly once across both requests — never charges twice").hasSize(1);
    }

    @Test
    void simultaneousCaptureRequestsDispatchOnlyOnce() throws Exception {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = createOpenPayment(buyerSub, providerOrderId);
        stubGrant(orderId, "{\"outcome\":\"GRANTED\",\"checkoutPhase\":\"CAPTURE_AUTHORIZED\"}");
        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH + "/" + providerOrderId + "/capture"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"CAP-" + UUID.randomUUID() + "\",\"status\":\"COMPLETED\"}")));

        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ResponseEntity<Map>> first = CompletableFuture.supplyAsync(() -> {
            await(start);
            return capture(orderId, buyerSub);
        });
        CompletableFuture<ResponseEntity<Map>> second = CompletableFuture.supplyAsync(() -> {
            await(start);
            return capture(orderId, buyerSub);
        });
        start.countDown();

        assertThat(first.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.get(10, TimeUnit.SECONDS).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(CREATE_PATH + "/" + providerOrderId + "/capture"))))
                .hasSize(1);
        assertThat(paymentRepository.findById(orderId).orElseThrow().getCaptureDispatchedAt()).isNotNull();
    }

    private void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private void stubOAuthToken() {
        PAYPAL.stubFor(post(urlEqualTo(OAUTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
    }

    private void stubGrant(String orderId, String responseBody) {
        ORDER_SERVICE.stubFor(post(urlEqualTo("/internal/orders/" + orderId + "/capture-authorizations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    /** Creates a real, persisted OPEN Payment row via the actual Task 1 create path. */
    private String createOpenPayment(String buyerSub, String providerOrderId) {
        stubOAuthToken();
        String orderId = UUID.randomUUID().toString();
        String approvalUrl = "https://www.sandbox.paypal.com/checkoutnow?token=" + providerOrderId;
        PAYPAL.stubFor(post(urlEqualTo(CREATE_PATH))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + providerOrderId + "\",\"status\":\"PAYER_ACTION_REQUIRED\","
                                + "\"links\":[{\"href\":\"" + approvalUrl + "\",\"rel\":\"payer-action\"}]}")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("buyerSub", buyerSub);
        body.put("itemPrice", new BigDecimal("10.00"));
        body.put("shippingCost", new BigDecimal("0.00"));
        body.put("pricePaid", new BigDecimal("10.00"));
        body.put("listingTitle", "Amiga 500");
        body.put("method", "PAYPAL");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange("/internal/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return orderId;
    }

    private ResponseEntity<Map> capture(String orderId, String buyerSub) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(buyerSub));
        return restTemplate.exchange("/payments/" + orderId + "/capture", HttpMethod.POST,
                new HttpEntity<>(headers), Map.class);
    }
}
