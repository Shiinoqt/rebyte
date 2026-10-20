package it.rebyte.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
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
import org.springframework.test.context.ActiveProfiles;

import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.service.PaymentWebhookService;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

/**
 * {@code GET /internal/payments/{orderId}/status} (03-11-PLAN.md
 * orchestrator-directed wiring fix, closing 03-10's D-29 gap): order-
 * service's admin stuck-order view consumes this instead of reading
 * payment_db directly.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles({"local-auth", "test"})
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
class PaymentStatusInternalIT extends PaymentIntegrationTestBase {

    private static final String OAUTH_PATH = "/v1/oauth2/token";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentWebhookService webhooks;

    @Test
    void unknownOrderReturns404() {
        ResponseEntity<Map> response = status(UUID.randomUUID().toString());
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openPaymentWithNoProviderOrderReportsOpenStateAndNoProviderId() {
        String orderId = createOpenPaymentWithNoProviderOrder();

        ResponseEntity<Map> response = status(orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("orderId")).isEqualTo(orderId);
        assertThat(response.getBody().get("state")).isEqualTo(PaymentState.OPEN.name());
        assertThat(response.getBody().get("providerOrderId")).isNull();
        assertThat(response.getBody().get("capturedAwaitingWebhook")).isEqualTo(false);
    }

    @Test
    void capturedAwaitingWebhookIsVisibleBeforeAnyWebhookArrives() {
        String captureId = "CAP-" + UUID.randomUUID();
        String orderId = openPaymentApprovedAndDispatched(captureId);

        ResponseEntity<Map> response = status(orderId);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("state")).isEqualTo(PaymentState.CAPTURING.name());
        assertThat(response.getBody().get("capturedAwaitingWebhook")).isEqualTo(false);

        // The reconciliation sweep is what flips capturedAwaitingWebhook (03-10);
        // this focused test proves the field round-trips once set, without
        // depending on PaymentReconciliationService's scheduling.
        payments.flagCapturedAwaitingWebhook(orderId, "COMPLETED");

        ResponseEntity<Map> flagged = status(orderId);
        assertThat(flagged.getBody().get("capturedAwaitingWebhook")).isEqualTo(true);
        assertThat(flagged.getBody().get("lastPayPalStatus")).isEqualTo("COMPLETED");
    }

    private ResponseEntity<Map> status(String orderId) {
        return restTemplate.exchange("/internal/payments/" + orderId + "/status", HttpMethod.GET, null, Map.class);
    }

    private void stubOAuthToken() {
        PAYPAL.stubFor(post(urlEqualTo(OAUTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
    }

    /** A Payment row that exists but never recorded a provider order id — the create call itself is still pending/unresolved. */
    private String createOpenPaymentWithNoProviderOrder() {
        String orderId = UUID.randomUUID().toString();
        String buyerSub = "buyer-" + UUID.randomUUID();
        stubOAuthToken();
        PAYPAL.stubFor(post(urlEqualTo("/v2/checkout/orders"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(5000)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

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
        // application.yml's PayPal read-timeout is 800ms in the test profile: the
        // create call times out client-side, leaving the row OPEN with no provider id.
        restTemplate.exchange("/internal/payments", HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        return orderId;
    }

    private String openPaymentApprovedAndDispatched(String captureId) {
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
        ResponseEntity<Map> created = restTemplate.exchange("/internal/payments", HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);

        payments.claimCapturing(orderId, "op-" + UUID.randomUUID(), "req-" + UUID.randomUUID(), PaymentState.OPEN,
                PaymentState.CAPTURING);
        payments.claimCaptureDispatch(orderId, PaymentState.CAPTURING, Instant.now());
        payments.recordCapture(orderId, PaymentState.CAPTURING, captureId, "COMPLETED");
        return orderId;
    }
}
