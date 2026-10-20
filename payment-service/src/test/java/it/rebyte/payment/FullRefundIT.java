package it.rebyte.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentMethod;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.service.PaymentWebhookService;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

/**
 * 03-10-PLAN.md Task 2 (D-27): one full refund request per verified capture,
 * mandatory reason, always the full amount via an empty JSON body, and
 * {@code payment.refunded} only after a verified {@code
 * PAYMENT.CAPTURE.REFUNDED} webhook — never from the synchronous refund
 * response alone.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"local-auth", "test"})
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
class FullRefundIT extends PaymentIntegrationTestBase {

    private static final String OAUTH_PATH = "/v1/oauth2/token";
    private static final String REFUND_PATH_PREFIX = "/v2/payments/captures/";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentWebhookService webhooks;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void oneFullRefundRequestSentPerCaptureWithMandatoryReasonAndEmptyBody() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = verifiedCapturedPayment(captureId);
        stubRefund(captureId, "{\"id\":\"REF-" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");

        ResponseEntity<Map> response = refund(orderId, "buyer requested a return");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status")).isEqualTo("REFUND_PENDING");
        assertThat(response.getBody().get("orderId")).isEqualTo(orderId);
        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(REFUND_PATH_PREFIX + captureId + "/refund"))
                .withRequestBody(equalToJson("{}"))))
                .as("exactly one full-amount (empty body) refund request").hasSize(1);
        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getRefundReason()).isEqualTo("buyer requested a return");
        assertThat(payment.getRefundRequestId()).isNotNull();
        assertThat(payment.getRefundDispatchedAt()).isNotNull();
    }

    @Test
    void repeatedRefundRequestNeverSendsSecondProviderCall() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = verifiedCapturedPayment(captureId);
        stubRefund(captureId, "{\"id\":\"REF-" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");

        ResponseEntity<Map> first = refund(orderId, "buyer requested a return");
        String requestIdAfterFirst = payments.findById(orderId).orElseThrow().getRefundRequestId();

        // A different reason on the retry is ignored — the original claim stands.
        ResponseEntity<Map> second = refund(orderId, "a different later reason");

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getRefundReason()).isEqualTo("buyer requested a return");
        assertThat(payment.getRefundRequestId()).isEqualTo(requestIdAfterFirst);
        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(REFUND_PATH_PREFIX + captureId + "/refund"))))
                .as("never a second provider refund call").hasSize(1);
    }

    @Test
    void refundRejectedWithoutWebhookVerifiedCapture() {
        // Captured optimistically (synchronous PayPal response) but no webhook has
        // confirmed it yet — D-26's "captured, awaiting webhook" — not refund-eligible.
        String orderId = openPayment();
        stubOAuthToken();
        payments.claimCapturing(orderId, "op-" + UUID.randomUUID(), "req-" + UUID.randomUUID(),
                PaymentState.OPEN, PaymentState.CAPTURING);
        payments.claimCaptureDispatch(orderId, PaymentState.CAPTURING, Instant.now());
        payments.recordCapture(orderId, PaymentState.CAPTURING, "CAP-" + UUID.randomUUID(), "COMPLETED");

        ResponseEntity<Map> response = refund(orderId, "buyer requested a return");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title")).isEqualTo("refund-not-eligible");
        assertThat(PAYPAL.findAll(postRequestedFor(urlMatching("/v2/payments/captures/.*"))))
                .as("never captured awaiting webhook to a provider call").isEmpty();
    }

    @Test
    void blankReasonRejected() {
        String orderId = verifiedCapturedPayment("CAP-" + UUID.randomUUID());

        ResponseEntity<Map> response = refund(orderId, "   ");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(payments.findById(orderId).orElseThrow().getRefundRequestId())
                .as("no claim without a mandatory reason").isNull();
    }

    @Test
    void provider5xxPropagatesAsErrorAndNeverEmitsRefundedEvent() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = verifiedCapturedPayment(captureId);
        stubOAuthToken();
        PAYPAL.stubFor(post(urlEqualTo(REFUND_PATH_PREFIX + captureId + "/refund"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"INTERNAL_SERVER_ERROR\"}")));

        ResponseEntity<Map> response = refund(orderId, "buyer requested a return");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(countOutboxEvents(orderId, "payment.refunded")).isZero();
    }

    @Test
    void providerTimeoutPropagatesAsError() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = verifiedCapturedPayment(captureId);
        stubOAuthToken();
        // application.yml: PayPal read-timeout is 800ms in this test base.
        PAYPAL.stubFor(post(urlEqualTo(REFUND_PATH_PREFIX + captureId + "/refund"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)
                        .withHeader("Content-Type", "application/json").withBody("{\"id\":\"x\",\"status\":\"PENDING\"}")));

        ResponseEntity<Map> response = refund(orderId, "buyer requested a return");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
        assertThat(countOutboxEvents(orderId, "payment.refunded")).isZero();
    }

    @Test
    void provider422PropagatesAsError() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = verifiedCapturedPayment(captureId);
        stubOAuthToken();
        PAYPAL.stubFor(post(urlEqualTo(REFUND_PATH_PREFIX + captureId + "/refund"))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"CAPTURE_FULLY_REFUNDED\"}]}")));

        ResponseEntity<Map> response = refund(orderId, "buyer requested a return");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(countOutboxEvents(orderId, "payment.refunded")).isZero();
    }

    @Test
    void verifiedWebhookAloneProducesPaymentRefundedEvent() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = verifiedCapturedPayment(captureId);
        stubRefund(captureId, "{\"id\":\"REF-" + UUID.randomUUID() + "\",\"status\":\"PENDING\"}");
        refund(orderId, "buyer requested a return");
        assertThat(countOutboxEvents(orderId, "payment.refunded"))
                .as("the synchronous refund response alone never emits the event").isZero();

        webhooks.acceptVerified(refundedEvent(UUID.randomUUID().toString(), captureId));

        assertThat(countOutboxEvents(orderId, "payment.refunded")).isEqualTo(1);
        assertThat(payments.findById(orderId).orElseThrow().getProviderCaptureStatus()).isEqualTo("REFUNDED");
    }

    // --- fixtures & stubs ---

    private void stubOAuthToken() {
        PAYPAL.stubFor(post(urlEqualTo(OAUTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
    }

    private void stubRefund(String captureId, String body) {
        stubOAuthToken();
        PAYPAL.stubFor(post(urlEqualTo(REFUND_PATH_PREFIX + captureId + "/refund"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body)));
    }

    /** OPEN Payment row with a recorded provider order id, via the real create path (WireMock). */
    private String openPayment() {
        String orderId = UUID.randomUUID().toString();
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        stubOAuthToken();
        PAYPAL.stubFor(post(urlEqualTo("/v2/checkout/orders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"" + providerOrderId + "\",\"status\":\"PAYER_ACTION_REQUIRED\","
                                + "\"links\":[{\"href\":\"https://example.invalid/approve\",\"rel\":\"payer-action\"}]}")));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("buyerSub", buyerSub);
        body.put("itemPrice", new BigDecimal("12.37"));
        body.put("shippingCost", new BigDecimal("4.19"));
        body.put("pricePaid", new BigDecimal("16.56"));
        body.put("listingTitle", "Amiga 500");
        body.put("method", "PAYPAL");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<Map> response = restTemplate.exchange("/internal/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return orderId;
    }

    /** A Payment whose capture is webhook-verified COMPLETED — the only refund-eligible state. */
    private String verifiedCapturedPayment(String captureId) {
        String orderId = openPayment();
        Payment payment = payments.findById(orderId).orElseThrow();
        webhooks.acceptVerified(captureEvent(UUID.randomUUID().toString(), orderId, payment.getProviderOrderId(),
                captureId));
        assertThat(payments.findById(orderId).orElseThrow().isVerifiedCapture()).isTrue();
        return orderId;
    }

    private byte[] captureEvent(String eventId, String orderId, String providerOrderId, String captureId) {
        return ("{\"id\":\"" + eventId + "\",\"event_type\":\"PAYMENT.CAPTURE.COMPLETED\","
                + "\"resource\":{\"id\":\"" + captureId + "\",\"custom_id\":\"" + orderId + "\","
                + "\"amount\":{\"currency_code\":\"EUR\",\"value\":\"16.56\"},"
                + "\"supplementary_data\":{\"related_ids\":{\"order_id\":\"" + providerOrderId + "\"}}}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private byte[] refundedEvent(String eventId, String captureId) {
        return ("{\"id\":\"" + eventId + "\",\"event_type\":\"PAYMENT.CAPTURE.REFUNDED\","
                + "\"resource\":{\"id\":\"REF-" + UUID.randomUUID() + "\","
                + "\"amount\":{\"currency_code\":\"EUR\",\"value\":\"16.56\"},"
                + "\"links\":[{\"rel\":\"self\",\"href\":\"https://api.sandbox.paypal.com/v2/payments/refunds/REF\"},"
                + "{\"rel\":\"up\",\"href\":\"https://api.sandbox.paypal.com/v2/payments/captures/" + captureId + "\"}]}}")
                .getBytes(StandardCharsets.UTF_8);
    }

    private ResponseEntity<Map> refund(String orderId, String reason) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> body = Map.of("reason", reason);
        return restTemplate.exchange("/internal/payments/" + orderId + "/refund", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    private long countOutboxEvents(String orderId, String eventType) {
        return jdbc.queryForObject(
                "select count(*) from payment_outbox_events where aggregate_id = ? and event_type = ?",
                Long.class, orderId, eventType);
    }
}
