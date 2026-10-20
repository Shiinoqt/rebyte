package it.rebyte.payment;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.client.OrderCaptureAuthorizationClient;
import it.rebyte.payment.outbox.DomainEventRecorder;
import it.rebyte.payment.provider.PayPalClient;
import it.rebyte.payment.domain.PaymentMethod;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.error.PaymentClosedException;
import it.rebyte.payment.repository.PaymentRepository;
import it.rebyte.payment.service.PaymentReconciliationService;
import it.rebyte.payment.service.PaymentReconciliationService.SweepResult;
import it.rebyte.payment.service.PaymentService;
import it.rebyte.payment.support.PaymentIntegrationTestBase;

/**
 * 03-10-PLAN.md Task 1 (D-24, D-25, D-26, D-29). Every test builds its own
 * Payment row directly through the repository (no HTTP), matching the
 * fixture style already used by {@code PaymentWebhookDeliveryIT}, then
 * drives {@link PaymentReconciliationService#sweepOnce()} synchronously —
 * never the real {@code @Scheduled} tick, which this class disables via
 * {@code @TestPropertySource}.
 *
 * <p><b>Cross-class database sharing (see {@code PaymentIntegrationTestBase}
 * javadoc):</b> the {@code payments} table is a single MySQL container
 * reused by every {@code *IT} class in this module, so a sweep here can also
 * see rows left behind by unrelated test classes. Every assertion below is
 * therefore scoped to this test's own {@code orderId}/{@code
 * providerOrderId} — never to the sweep's aggregate {@link SweepResult}
 * counts, which are not test-isolated. {@code offerExpiryHookIsCalledEachCycle}
 * is the one exception: the offer-expiry hook is called exactly once per
 * {@code sweepOnce()} regardless of how many payment rows exist, so its
 * count is safe to assert directly.
 */
@SpringBootTest
@ActiveProfiles({"local-auth", "test"})
@TestPropertySource(properties = {
        // Every row this class inserts is immediately "due" regardless of createdAt.
        "rebyte.checkout.payment-timeout=PT0S",
        // Tests call sweepOnce() directly; the real scheduled tick must not also fire.
        "rebyte.checkout.reconciliation.fixed-delay-ms=3600000",
        "rebyte.checkout.reconciliation.initial-delay-ms=3600000"
})
@Import(PaymentIntegrationTestBase.StubJwtConfig.class)
class ReconciliationSweepIT extends PaymentIntegrationTestBase {

    private static final String OAUTH_PATH = "/v1/oauth2/token";
    private static final String ORDERS_PATH = "/v2/checkout/orders";
    private static final String EXPIRE_DUE_PATH = "/internal/offers/expire-due";
    private static final String RESOURCE_NOT_FOUND_BODY = "{\"name\":\"RESOURCE_NOT_FOUND\","
            + "\"message\":\"The specified resource does not exist\","
            + "\"details\":[{\"issue\":\"INVALID_RESOURCE_ID\",\"description\":\"Specified resource ID does not exist\"}]}";

    @Autowired
    private PaymentReconciliationService reconciliation;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired private DomainEventRecorder recorder;
    @Autowired private PayPalClient payPalClient;
    @Autowired private OrderCaptureAuthorizationClient orderClient;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void expiryAndTerminalFenceRollBackTogetherWithOutboxOnRecordingFailure() {
        for (boolean claimed : List.of(false, true)) {
            String providerOrderId = "PPO-" + UUID.randomUUID();
            String orderId = openPayment(providerOrderId);
            if (claimed) claimCapturing(orderId);
            stubGetOrder(providerOrderId, 404, RESOURCE_NOT_FOUND_BODY);
            DomainEventRecorder failingRecorder = spy(recorder);
            doAnswer(invocation -> {
                invocation.callRealMethod();
                throw new IllegalStateException("injected outbox failure");
            }).when(failingRecorder).record(any(), any(), any());

            sweepWith(failingRecorder).sweepOnce();

            assertThat(payments.findById(orderId).orElseThrow().getState())
                    .isEqualTo(claimed ? PaymentState.CAPTURING : PaymentState.OPEN);
            assertThat(countOutboxEvents(orderId, "payment.expired")).isZero();
            sweepWith(recorder).sweepOnce();
            assertThat(payments.findById(orderId).orElseThrow().getState())
                    .isEqualTo(claimed ? PaymentState.TERMINAL_NO_CHARGE : PaymentState.CLOSED);
            assertThat(countOutboxEvents(orderId, "payment.expired")).isEqualTo(1);
        }
    }

    @Test
    void boundedSweepsAdvancePastMoreThanOneBatchOfUnresolvedRowsWithEqualTimestamps() {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        Instant createdAt = Instant.parse("2020-01-01T00:00:00Z");
        List<String> ids = new ArrayList<>();
        try {
            for (int i = 0; i < 52; i++) {
                String id = prefix + "-" + String.format("%03d", i);
                ids.add(id);
                Payment payment = new Payment(id, "buyer", new BigDecimal("10.00"), BigDecimal.ZERO,
                        new BigDecimal("10.00"), "Amiga", PaymentMethod.PAYPAL, "create-" + id, createdAt);
                payments.saveAndFlush(payment);
                if (i < 51) {
                    payments.recordProviderOrder(id, "PPO-" + id, "https://example.invalid");
                    claimCapturing(id);
                    payments.recordCapture(id, PaymentState.CAPTURING, "CAP-" + id, "COMPLETED");
                }
            }
            PaymentReconciliationService sweep = sweepWith(recorder);
            assertThat(sweep.sweepOnce().reconciledCount()).isEqualTo(50);
            assertThat(payments.findById(ids.get(51)).orElseThrow().getState()).isEqualTo(PaymentState.OPEN);

            assertThat(sweep.sweepOnce().reconciledCount()).isLessThanOrEqualTo(50);

            assertThat(payments.findById(ids.get(51)).orElseThrow().getState()).isEqualTo(PaymentState.CLOSED);
            assertThat(countOutboxEvents(ids.get(51), "payment.expired")).isEqualTo(1);
            for (String id : ids.subList(0, 51)) {
                assertThat(payments.findById(id).orElseThrow().isCapturedAwaitingWebhook()).isTrue();
            }
        } finally {
            payments.deleteAllById(ids);
        }
    }

    @Test
    void terminalFenceCannotOverwriteARecordedCapture() {
        String orderId = openPayment("PPO-" + UUID.randomUUID());
        claimCapturing(orderId);
        payments.recordCapture(orderId, PaymentState.CAPTURING, "CAP-" + UUID.randomUUID(), "COMPLETED");
        assertThat(payments.fenceTerminalNoCharge(orderId, PaymentState.CAPTURING,
                PaymentState.TERMINAL_NO_CHARGE, "RESOURCE_NOT_FOUND")).isZero();
        assertThat(payments.findById(orderId).orElseThrow().getState()).isEqualTo(PaymentState.CAPTURING);
    }

    private PaymentReconciliationService sweepWith(DomainEventRecorder eventRecorder) {
        return new PaymentReconciliationService(payments, payPalClient, paymentService, eventRecorder,
                orderClient, transactionManager, Duration.ZERO, 50);
    }

    @Test
    void approvedOrderCausesOneStableIdCaptureRequest() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        stubGetOrder(providerOrderId, 200, "{\"id\":\"" + providerOrderId + "\",\"status\":\"APPROVED\"}");
        stubGrant(orderId, "{\"outcome\":\"GRANTED\",\"checkoutPhase\":\"CAPTURE_AUTHORIZED\"}");
        String captureId = "CAP-" + UUID.randomUUID();
        stubCapture(providerOrderId, "{\"id\":\"" + captureId + "\",\"status\":\"COMPLETED\"}");

        reconciliation.sweepOnce();

        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(ORDERS_PATH + "/" + providerOrderId + "/capture"))))
                .as("one stable-ID capture request").hasSize(1);
        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.CAPTURING);
        assertThat(payment.getProviderCaptureId()).isEqualTo(captureId);
    }

    @Test
    void unclaimedCreatedOrderExpiresExactlyOnce() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        stubGetOrder(providerOrderId, 200, "{\"id\":\"" + providerOrderId + "\",\"status\":\"CREATED\"}");

        reconciliation.sweepOnce();

        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.CLOSED);
        assertThat(payment.getCaptureOperationId()).isNull();
        assertThat(countOutboxEvents(orderId, "payment.expired")).isEqualTo(1);

        // Row is CLOSED now — excluded from the next due-query; no second event.
        reconciliation.sweepOnce();
        assertThat(countOutboxEvents(orderId, "payment.expired")).isEqualTo(1);
    }

    @Test
    void completedWithoutWebhookRemainsUnpaidAndFlagged() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        stubGetOrder(providerOrderId, 200, "{\"id\":\"" + providerOrderId + "\",\"status\":\"APPROVED\"}");
        stubGrant(orderId, "{\"outcome\":\"GRANTED\",\"checkoutPhase\":\"CAPTURE_AUTHORIZED\"}");
        stubCapture(providerOrderId, "{\"id\":\"CAP-" + UUID.randomUUID() + "\",\"status\":\"COMPLETED\"}");

        reconciliation.sweepOnce();
        int getCallsAfterFirstCycle = PAYPAL.findAll(getRequestedFor(urlEqualTo(ORDERS_PATH + "/" + providerOrderId)))
                .size();

        // Second cycle: providerCaptureId is already known locally — no redundant GET.
        reconciliation.sweepOnce();

        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.CAPTURING);
        assertThat(payment.isCapturedAwaitingWebhook()).isTrue();
        assertThat(payment.getLastPayPalStatus()).isEqualTo("COMPLETED");
        assertThat(PAYPAL.findAll(getRequestedFor(urlEqualTo(ORDERS_PATH + "/" + providerOrderId))))
                .as("providerCaptureId already known: no redundant GET on the second cycle")
                .hasSize(getCallsAfterFirstCycle);
        assertThat(countOutboxEvents(orderId, "payment.succeeded")).as("stays unpaid: no webhook arrived").isZero();
    }

    @Test
    void unknownGetRetainsGuardForNextReconciliation() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        claimCapturing(orderId);
        stubOAuthToken();
        PAYPAL.stubFor(get(urlEqualTo(ORDERS_PATH + "/" + providerOrderId))
                .willReturn(aResponse().withStatus(422)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"name\":\"UNPROCESSABLE_ENTITY\",\"details\":[{\"issue\":\"ORDER_NOT_APPROVED\"}]}")));

        reconciliation.sweepOnce();

        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.CAPTURING);
        assertThat(payment.getProviderCaptureId()).isNull();
        assertThat(countOutboxEvents(orderId, "payment.expired")).isZero();
    }

    @Test
    void crashLostGrantRecoversOnNextSweep() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        // The OPEN -> CAPTURING claim committed (operation id minted) but the process
        // crashed before ever requesting the order-service grant or claiming dispatch.
        claimCapturing(orderId);
        String operationIdBefore = payments.findById(orderId).orElseThrow().getCaptureOperationId();
        stubGetOrder(providerOrderId, 200, "{\"id\":\"" + providerOrderId + "\",\"status\":\"APPROVED\"}");
        stubGrant(orderId, "{\"outcome\":\"GRANTED\",\"checkoutPhase\":\"CAPTURE_AUTHORIZED\"}");
        String captureId = "CAP-" + UUID.randomUUID();
        stubCapture(providerOrderId, "{\"id\":\"" + captureId + "\",\"status\":\"COMPLETED\"}");

        reconciliation.sweepOnce();

        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getCaptureOperationId()).as("reuses the already-minted operation id")
                .isEqualTo(operationIdBefore);
        assertThat(payment.getProviderCaptureId()).isEqualTo(captureId);
        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(ORDERS_PATH + "/" + providerOrderId + "/capture"))))
                .hasSize(1);
    }

    @Test
    void providerFinalExpiredCaptureFencesClaimAndEmitsOneEvent() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        claimCapturingAndDispatch(orderId);
        stubGetOrder(providerOrderId, 404, RESOURCE_NOT_FOUND_BODY);

        reconciliation.sweepOnce();

        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.TERMINAL_NO_CHARGE);
        assertThat(payment.getLastPayPalStatus()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(payment.getProviderCaptureId()).isNull();
        assertThat(countOutboxEvents(orderId, "payment.expired")).isEqualTo(1);

        // Row is TERMINAL_NO_CHARGE now — excluded from the next due-query; no second event.
        reconciliation.sweepOnce();
        assertThat(countOutboxEvents(orderId, "payment.expired")).isEqualTo(1);
    }

    @Test
    void pausedWorkerAfterClosureSendsNoRequest() {
        String buyerSub = "buyer-" + UUID.randomUUID();
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPaymentForBuyer(buyerSub, providerOrderId);
        // Represents a worker that already claimed the dispatch fence and paused right
        // before it would call PayPal.
        claimCapturingAndDispatch(orderId);
        stubGetOrder(providerOrderId, 404, RESOURCE_NOT_FOUND_BODY);
        reconciliation.sweepOnce();
        assertThat(payments.findById(orderId).orElseThrow().getState()).isEqualTo(PaymentState.TERMINAL_NO_CHARGE);

        // The paused worker "wakes up" and retries the same stable-ID capture path.
        assertThatThrownBy(() -> paymentService.capture(orderId, buyerSub)).isInstanceOf(PaymentClosedException.class);

        assertThat(PAYPAL.findAll(postRequestedFor(urlEqualTo(ORDERS_PATH + "/" + providerOrderId + "/capture"))))
                .as("a paused worker rechecks terminal state before dispatch and sends no request").isEmpty();
    }

    @Test
    void inFlightDelayedCaptureCannotChargeAfterProviderFinality() {
        String providerOrderId = "PPO-" + UUID.randomUUID();
        String orderId = openPayment(providerOrderId);
        // Dispatch already claimed: represents a capture POST already sent to PayPal
        // with its response still pending ("in flight") when the sweep runs.
        claimCapturingAndDispatch(orderId);
        stubGetOrder(providerOrderId, 404, RESOURCE_NOT_FOUND_BODY);

        reconciliation.sweepOnce();
        assertThat(payments.findById(orderId).orElseThrow().getState()).isEqualTo(PaymentState.TERMINAL_NO_CHARGE);

        // The delayed in-flight call finally "returns" — recordCapture is the exact
        // write a genuine response would perform; it must be a no-op once the row has
        // moved off CAPTURING, per PayPal's own resource-gone guarantee (the same
        // order id a capture would target no longer exists either).
        int updated = payments.recordCapture(orderId, PaymentState.CAPTURING, "CAP-late", "COMPLETED");

        assertThat(updated).isZero();
        Payment payment = payments.findById(orderId).orElseThrow();
        assertThat(payment.getState()).isEqualTo(PaymentState.TERMINAL_NO_CHARGE);
        assertThat(payment.getProviderCaptureId()).isNull();
        assertThat(countOutboxEvents(orderId, "payment.expired")).isEqualTo(1);
        assertThat(countOutboxEvents(orderId, "payment.succeeded")).isZero();
    }

    @Test
    void offerExpiryHookIsCalledEachCycle() {
        ORDER_SERVICE.stubFor(post(urlEqualTo(EXPIRE_DUE_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"expiredCount\":3}")));

        SweepResult result = reconciliation.sweepOnce();

        assertThat(result.expiredOfferCount()).isEqualTo(3);
        assertThat(ORDER_SERVICE.findAll(postRequestedFor(urlEqualTo(EXPIRE_DUE_PATH)))).hasSize(1);
    }

    // --- fixtures & stubs ---

    private void stubOAuthToken() {
        PAYPAL.stubFor(post(urlEqualTo(OAUTH_PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"access_token\":\"test-access-token\",\"token_type\":\"Bearer\",\"expires_in\":32400}")));
    }

    private void stubGetOrder(String providerOrderId, int status, String body) {
        stubOAuthToken();
        PAYPAL.stubFor(get(urlEqualTo(ORDERS_PATH + "/" + providerOrderId))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubCapture(String providerOrderId, String body) {
        PAYPAL.stubFor(post(urlEqualTo(ORDERS_PATH + "/" + providerOrderId + "/capture"))
                .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubGrant(String orderId, String responseBody) {
        ORDER_SERVICE.stubFor(post(urlEqualTo("/internal/orders/" + orderId + "/capture-authorizations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(responseBody)));
    }

    /** Persists a real OPEN Payment row with a recorded provider order id — no HTTP involved. */
    private String openPayment(String providerOrderId) {
        return openPaymentForBuyer("buyer-" + UUID.randomUUID(), providerOrderId);
    }

    private String openPaymentForBuyer(String buyerSub, String providerOrderId) {
        String orderId = UUID.randomUUID().toString();
        Payment payment = new Payment(orderId, buyerSub, new BigDecimal("10.00"), new BigDecimal("0.00"),
                new BigDecimal("10.00"), "Amiga 500", PaymentMethod.PAYPAL, "create-" + orderId, Instant.now());
        payments.saveAndFlush(payment);
        payments.recordProviderOrder(orderId, providerOrderId,
                "https://www.sandbox.paypal.com/checkoutnow?token=" + providerOrderId);
        return orderId;
    }

    /** OPEN -> CAPTURING: mints the durable operation/request id pair, as the real capture claim would. */
    private void claimCapturing(String orderId) {
        int updated = payments.claimCapturing(orderId, "op-" + UUID.randomUUID(), "req-" + UUID.randomUUID(),
                PaymentState.OPEN, PaymentState.CAPTURING);
        assertThat(updated).isEqualTo(1);
    }

    /** As {@link #claimCapturing} but also claims the one-time PayPal dispatch fence. */
    private void claimCapturingAndDispatch(String orderId) {
        claimCapturing(orderId);
        int updated = payments.claimCaptureDispatch(orderId, PaymentState.CAPTURING, Instant.now());
        assertThat(updated).isEqualTo(1);
    }

    private long countOutboxEvents(String orderId, String eventType) {
        return jdbc.queryForObject(
                "select count(*) from payment_outbox_events where aggregate_id = ? and event_type = ?",
                Long.class, orderId, eventType);
    }
}
