package it.rebyte.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.fasterxml.jackson.databind.ObjectMapper;

import it.rebyte.order.client.PaymentInternalClient;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.order.dto.RefundRequest;
import it.rebyte.order.dto.RefundResponse;
import it.rebyte.order.dto.ProblemView;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.OrderPaymentTransitionService;
import it.rebyte.order.service.ProblemResolutionService;
import it.rebyte.order.support.OrderIntegrationTestBase;
import feign.FeignException;
import feign.Request;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("local-auth")
@Import(OrderIntegrationTestBase.StubJwtConfig.class)
class ProblemResolutionIT extends OrderIntegrationTestBase {

    @Autowired TestRestTemplate http;
    @MockitoSpyBean OrderRepository orders;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired OrderPaymentTransitionService transitions;
    @MockitoBean PaymentInternalClient payments;
    @Autowired ProblemResolutionService resolution;
    @Autowired DataSource dataSource;

    @Test
    void adminRefundClaimsAProblemRevertedToOpenWhileItWaitedForTheOrderLock() throws Exception {
        String id = seed("SHIPPED");
        String problemId = (String) report(id).get("id");
        jdbc.update("update order_problem set status='REFUND_PENDING', resolved_by='seller', "
                + "resolution_note='Earlier claim' where id=?", problemId);
        when(payments.refund(eq(id), any(RefundRequest.class)))
                .thenReturn(new RefundResponse(id, "REFUND_PENDING"));
        clearInvocations(orders);

        CompletableFuture<ProblemView> future;
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            boolean committed = false;
            try {
                try (PreparedStatement lock = holder.prepareStatement("select id from orders where id=? for update")) {
                    lock.setString(1, id);
                    try (var rows = lock.executeQuery()) {
                        assertThat(rows.next()).isTrue();
                    }
                }
                future = CompletableFuture.supplyAsync(
                        () -> resolution.refundByAdmin(problemId, "operator", "Admin retry"));
                verify(orders, timeout(10_000)).findByIdForUpdate(id);
                try (PreparedStatement revert = holder.prepareStatement("update order_problem set status='OPEN', "
                        + "resolved_by=null, resolution_note=null where id=?")) {
                    revert.setString(1, problemId);
                    assertThat(revert.executeUpdate()).isEqualTo(1);
                }
                holder.commit();
                committed = true;
            } finally {
                if (!committed) {
                    holder.rollback();
                }
            }
        }

        ProblemView view = future.get(10, TimeUnit.SECONDS);
        assertThat(view.status().name()).isEqualTo("REFUND_PENDING");
        assertThat(view.resolutionNote()).isEqualTo("Admin retry");
        assertPendingClaim(id, "operator", "Admin retry");
        verify(payments).refund(eq(id), argThat(request -> "PROBLEM_REFUND".equals(request.reason())));
    }

    @Test
    void sellerRefundStaysPendingUntilWebhookAndIsReplaySafe() throws Exception {
        String id = seed("SHIPPED");
        var report = report(id);
        when(payments.refund(eq(id), any(RefundRequest.class))).thenReturn(new RefundResponse(id, "REFUND_PENDING"));

        ResponseEntity<Map> response = post("/orders/" + id + "/problem/refund", "seller",
                Map.of("note", "Refunding in full"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).containsEntry("id", report.get("id"))
                .containsEntry("status", "REFUND_PENDING").containsEntry("resolutionNote", "Refunding in full");
        verify(payments).refund(eq(id), org.mockito.ArgumentMatchers.argThat(request ->
                "PROBLEM_REFUND".equals(request.reason())));
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        assertThat(jdbc.queryForObject("select refund_requested_at is not null from orders where id=?", Boolean.class,
                id)).isTrue();

        assertError(post("/orders/" + id + "/problem/refund", "buyer", null), HttpStatus.FORBIDDEN,
                "order-action-forbidden");
        assertError(post("/orders/" + id + "/problem/refund", "stranger", null), HttpStatus.NOT_FOUND,
                "order-not-found");
        assertThat(post("/orders/" + id + "/problem/refund", "seller", null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);

        PaymentResultEvent refund = new PaymentResultEvent(UUID.randomUUID().toString(), id, "PP-" + id,
                "CAP-" + id, null, false, Instant.now());
        transitions.apply("payment.refunded", refund);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, id))
                .isEqualTo("REFUND");
        assertThat(jdbc.queryForObject("select resolved_at is not null from order_problem where order_id=?",
                Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.problem.resolved")).isEqualTo(1L);
        String resolvedPayload = jdbc.queryForObject("select payload from order_outbox_events where aggregate_id=? and event_type=?",
                String.class, id, "order.problem.resolved");
        assertThat(resolvedPayload).doesNotContain("Refunding in full", "A valid description");
        var refundedPayload = mapper.readTree(jdbc.queryForObject(
                "select payload from order_outbox_events where aggregate_id=? and event_type='order.refunded'",
                String.class, id));
        assertThat(refundedPayload.path("reservationId").isNull()).isTrue();

        transitions.apply("payment.refunded", refund);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.problem.resolved")).isEqualTo(1L);
        var resolvedAt = jdbc.queryForObject("select resolved_at from order_problem where order_id=?",
                java.sql.Timestamp.class, id);
        PaymentResultEvent replay = new PaymentResultEvent(UUID.randomUUID().toString(), id,
                refund.providerOrderId(), refund.providerCaptureId(), null, false, Instant.now());
        transitions.apply("payment.refunded", replay);
        assertThat(jdbc.queryForObject("select count(*) from order_inbox_events where event_id=?", Long.class,
                replay.eventId())).isEqualTo(1L);
        assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.refunded")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.problem.resolved")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, id))
                .isEqualTo("REFUND");
        assertThat(jdbc.queryForObject("select resolved_at from order_problem where order_id=?",
                java.sql.Timestamp.class, id)).isEqualTo(resolvedAt);
    }

    @Test
    void adminCanRefundOrDismissButOnlyWebhookCompletesRefund() throws Exception {
        String paid = seed("PAID");
        var paidReport = report(paid);
        when(payments.refund(eq(paid), any(RefundRequest.class))).thenReturn(new RefundResponse(paid, "REFUND_PENDING"));
        var refunded = post("/admin/problems/" + paidReport.get("id") + "/refund", "admin-operator", null);
        assertThat(refunded.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(refunded.getBody()).containsEntry("status", "REFUND_PENDING");
        PaymentResultEvent refund = new PaymentResultEvent(UUID.randomUUID().toString(), paid, "PP-" + paid,
                "CAP-" + paid, null, false, Instant.now());
        transitions.apply("payment.refunded", refund);
        var payload = mapper.readTree(jdbc.queryForObject(
                "select payload from order_outbox_events where aggregate_id=? and event_type='order.refunded'",
                String.class, paid));
        assertThat(payload.path("reservationId").asText()).isEqualTo(paid);
        assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, paid))
                .isEqualTo("REFUND");

        String delivered = seed("DELIVERED");
        Map dismissedReport = report(delivered);
        var dismissed = post("/admin/problems/" + dismissedReport.get("id") + "/dismiss", "admin-operator",
                Map.of("note", "Tracking shows delivery"));
        assertThat(dismissed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dismissed.getBody()).containsEntry("status", "RESOLVED").containsEntry("outcome", "DISMISS")
                .containsEntry("resolutionNote", "Tracking shows delivery");
        assertThat(orders.findById(delivered).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, delivered, "order.problem.resolved")).isEqualTo(1L);
        assertError(post("/admin/problems/" + dismissedReport.get("id") + "/dismiss", "admin-operator",
                Map.of("note", "Again")), HttpStatus.CONFLICT, "problem-not-open");
        assertError(post("/admin/problems/" + dismissedReport.get("id") + "/dismiss", "admin-operator",
                Map.of("note", " ")), HttpStatus.BAD_REQUEST, "Validation failed");

        String pending = seed("SHIPPED");
        Map pendingReport = report(pending);
        when(payments.refund(eq(pending), any(RefundRequest.class))).thenReturn(new RefundResponse(pending, "REFUND_PENDING"));
        assertThat(post("/orders/" + pending + "/problem/refund", "seller", null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertError(post("/admin/problems/" + pendingReport.get("id") + "/dismiss", "admin-operator",
                Map.of("note", "Still pending")), HttpStatus.CONFLICT, "problem-not-open");
        for (String subject : new String[] { "seller", "buyer" }) {
            assertThat(post("/admin/problems/" + pendingReport.get("id") + "/refund", subject, null)
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(post("/admin/problems/" + pendingReport.get("id") + "/dismiss", subject,
                    Map.of("note", "No access")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Test
    void refundFailuresRestoreOpenClaimsOrKeepUnknownClaimsRetryable() {
        Request request = Request.create(Request.HttpMethod.POST, "http://payments/refund", Map.of(), new byte[0],
                java.nio.charset.StandardCharsets.UTF_8, null);
        String conflict = seed("SHIPPED");
        Map conflictReport = report(conflict);
        when(payments.refund(eq(conflict), any(RefundRequest.class))).thenThrow(new RuntimeException(
                new FeignException.Conflict("ineligible", request, null, Map.of())));
        assertError(post("/orders/" + conflict + "/problem/refund", "seller", null), HttpStatus.CONFLICT,
                "refund-not-eligible");
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, conflict))
                .isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("select resolved_by is null from order_problem where order_id=?", Boolean.class,
                conflict)).isTrue();
        assertThat(orders.findById(conflict).orElseThrow().getRefundRequestedAt()).isNull();

        String unknown = seed("SHIPPED");
        Map unknownReport = report(unknown);
        when(payments.refund(eq(unknown), any(RefundRequest.class))).thenThrow(new FeignException.ServiceUnavailable(
                "unavailable", request, null, Map.of()));
        assertError(post("/orders/" + unknown + "/problem/refund", "seller", null),
                HttpStatus.SERVICE_UNAVAILABLE, "refund-unavailable");
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, unknown))
                .isEqualTo("REFUND_PENDING");
        when(payments.refund(eq(unknown), any(RefundRequest.class))).thenReturn(new RefundResponse(unknown,
                "REFUND_PENDING"));
        assertThat(post("/orders/" + unknown + "/problem/refund", "seller", null).getStatusCode())
                .isEqualTo(HttpStatus.ACCEPTED);
        assertThat(unknownReport).containsKey("id");
        assertThat(conflictReport).containsKey("id");
    }

    @Test
    void sellerAndAdminRetriesReopenAfterDefinitiveRefusalAndLateRefundResolvesOnce() {
        Request request = Request.create(Request.HttpMethod.POST, "http://payments/refund", Map.of(), new byte[0],
                java.nio.charset.StandardCharsets.UTF_8, null);
        FeignException.ServiceUnavailable unknown = new FeignException.ServiceUnavailable(
                "unavailable", request, null, Map.of());
        FeignException.Conflict refusal = new FeignException.Conflict("ineligible", request, null, Map.of());

        for (RefundRoute route : List.of(new RefundRoute("seller", false), new RefundRoute("admin-operator", true))) {
            String id = seed("SHIPPED");
            Map report = report(id);
            String actor = route.admin() ? "operator" : route.subject();
            when(payments.refund(eq(id), any(RefundRequest.class)))
                    .thenThrow(unknown)
                    .thenThrow(unknown)
                    .thenThrow(refusal)
                    .thenReturn(new RefundResponse(id, "REFUND_PENDING"));

            assertError(refund(route, id, (String) report.get("id"), "First retry note"),
                    HttpStatus.SERVICE_UNAVAILABLE, "refund-unavailable");
            assertPendingClaim(id, actor, "First retry note");

            assertError(refund(route, id, (String) report.get("id"), "Ignored retry note"),
                    HttpStatus.SERVICE_UNAVAILABLE, "refund-unavailable");
            assertPendingClaim(id, actor, "First retry note");

            assertError(refund(route, id, (String) report.get("id"), "Refused retry note"),
                    HttpStatus.CONFLICT, "refund-not-eligible");
            assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                    .isEqualTo("OPEN");
            assertThat(jdbc.queryForObject("select resolved_by is null from order_problem where order_id=?", Boolean.class,
                    id)).isTrue();
            assertThat(jdbc.queryForObject("select resolution_note is null from order_problem where order_id=?",
                    Boolean.class, id)).isTrue();
            assertThat(orders.findById(id).orElseThrow().getRefundRequestedAt()).isNull();

            PaymentResultEvent refund = new PaymentResultEvent(UUID.randomUUID().toString(), id, "PP-" + id,
                    "CAP-" + id, null, false, Instant.now());
            transitions.apply("payment.refunded", refund);
            transitions.apply("payment.refunded", refund);
            assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                    .isEqualTo("RESOLVED");
            assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, id))
                    .isEqualTo("REFUND");
            assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                    Long.class, id, "order.problem.resolved")).isEqualTo(1L);
            var resolvedAt = jdbc.queryForObject("select resolved_at from order_problem where order_id=?",
                    java.sql.Timestamp.class, id);
            PaymentResultEvent replay = new PaymentResultEvent(UUID.randomUUID().toString(), id,
                    refund.providerOrderId(), refund.providerCaptureId(), null, false, Instant.now());
            transitions.apply("payment.refunded", replay);
            assertThat(jdbc.queryForObject("select count(*) from order_inbox_events where event_id=?", Long.class,
                    replay.eventId())).isEqualTo(1L);
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.REFUNDED);
            assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                    Long.class, id, "order.refunded")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                    Long.class, id, "order.problem.resolved")).isEqualTo(1L);
            assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                    .isEqualTo("RESOLVED");
            assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, id))
                    .isEqualTo("REFUND");
            assertThat(jdbc.queryForObject("select resolved_at from order_problem where order_id=?",
                    java.sql.Timestamp.class, id)).isEqualTo(resolvedAt);
        }
    }

    @Test
    void lateRefusalCannotReopenProblemAlreadyResolvedByRefundWebhook() throws Exception {
        Request request = Request.create(Request.HttpMethod.POST, "http://payments/refund", Map.of(), new byte[0],
                java.nio.charset.StandardCharsets.UTF_8, null);
        String id = seed("SHIPPED");
        Map report = report(id);
        CountDownLatch refundCallStarted = new CountDownLatch(1);
        CountDownLatch returnRefusal = new CountDownLatch(1);
        when(payments.refund(eq(id), any(RefundRequest.class))).thenAnswer(invocation -> {
            refundCallStarted.countDown();
            if (!returnRefusal.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting to return the mocked refusal");
            }
            throw new FeignException.Conflict("ineligible", request, null, Map.of());
        });

        CompletableFuture<ResponseEntity<Map>> response = CompletableFuture.supplyAsync(
                () -> postWithoutRetries("/orders/" + id + "/problem/refund", "seller",
                        Map.of("note", "Late request")));
        assertThat(refundCallStarted.await(10, TimeUnit.SECONDS)).isTrue();
        try {
            transitions.apply("payment.refunded", new PaymentResultEvent(UUID.randomUUID().toString(), id,
                    "PP-" + id, "CAP-" + id, null, false, Instant.now()));
        } finally {
            returnRefusal.countDown();
        }

        assertError(response.get(10, TimeUnit.SECONDS), HttpStatus.CONFLICT, "refund-not-eligible");
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, id))
                .isEqualTo("REFUND");
        assertThat(jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.problem.resolved")).isEqualTo(1L);
        assertThat(report).containsKey("id");
    }

    @Test
    void adminOrderRefundAlsoResolvesAnOpenProblemOnWebhook() {
        String id = seed("PAID");
        report(id);
        when(payments.refund(eq(id), any(RefundRequest.class))).thenReturn(new RefundResponse(id, "REFUND_PENDING"));
        assertThat(post("/admin/orders/" + id + "/refund", "admin-operator", Map.of("reason", "ADMIN"))
                .getStatusCode()).isEqualTo(HttpStatus.OK);
        transitions.apply("payment.refunded", new PaymentResultEvent(UUID.randomUUID().toString(), id, "PP-" + id,
                "CAP-" + id, null, false, Instant.now()));
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, id))
                .isEqualTo("RESOLVED");
        assertThat(jdbc.queryForObject("select outcome from order_problem where order_id=?", String.class, id))
                .isEqualTo("REFUND");
    }

    private Map report(String orderId) {
        return post("/orders/" + orderId + "/problem", "buyer",
                Map.of("reason", "OTHER", "description", "A valid description")).getBody();
    }

    private void assertPendingClaim(String orderId, String actor, String note) {
        assertThat(jdbc.queryForObject("select status from order_problem where order_id=?", String.class, orderId))
                .isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("select resolved_by from order_problem where order_id=?", String.class, orderId))
                .isEqualTo(actor);
        assertThat(jdbc.queryForObject("select resolution_note from order_problem where order_id=?", String.class, orderId))
                .isEqualTo(note);
        assertThat(orders.findById(orderId).orElseThrow().getRefundRequestedAt()).isNotNull();
    }

    private ResponseEntity<Map> refund(RefundRoute route, String orderId, String problemId, String note) {
        String path = route.admin() ? "/admin/problems/" + problemId + "/refund"
                : "/orders/" + orderId + "/problem/refund";
        return postWithoutRetries(path, route.subject(), Map.of("note", note));
    }

    private ResponseEntity<Map> postWithoutRetries(String path, String subject, Object body) {
        // The default TestRestTemplate request factory retries 503 responses and consumes later mocked attempts.
        http.getRestTemplate().setRequestFactory(new SimpleClientHttpRequestFactory());
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private record RefundRoute(String subject, boolean admin) {
    }

    private String seed(String status) {
        String id = UUID.randomUUID().toString();
        orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(), ListingType.HARDWARE,
                "Private title", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800)));
        jdbc.update("update orders set status=?, provider_order_id=?, provider_capture_id=? where id=?",
                status, "PP-" + id, "CAP-" + id, id);
        if (status.equals("DELIVERED")) {
            Order delivered = orders.findById(id).orElseThrow();
            delivered.markDelivered(Instant.now());
            orders.saveAndFlush(delivered);
            assertThat(orders.findById(id).orElseThrow().getDeliveredAt()).isAfter(Instant.now().minusSeconds(60));
        }
        return id;
    }

    private ResponseEntity<Map> post(String path, String subject, Object body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token(subject));
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
    }

    private void assertError(ResponseEntity<Map> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody().toString()).contains(code);
    }
}
