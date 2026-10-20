package it.rebyte.order;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;
import feign.FeignException;
import feign.Request;
import it.rebyte.order.client.PaymentInternalClient;
import it.rebyte.order.domain.*;
import it.rebyte.order.dto.RefundResponse;
import it.rebyte.order.dto.ReportProblemRequest;
import it.rebyte.order.repository.OrderProblemRepository;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.service.*;
import it.rebyte.order.error.*;
import it.rebyte.order.support.OrderIntegrationTestBase;

@SpringBootTest
@org.junit.jupiter.api.Timeout(120)
class FulfilmentSweepIT extends OrderIntegrationTestBase {
    @Autowired OrderRepository orders;
    @Autowired OrderProblemRepository problems;
    @Autowired JdbcTemplate jdbc;
    FulfilmentSweepService sweep;
    @Autowired org.springframework.scheduling.TaskScheduler scheduler;
    @Autowired OrderAdminService admin;
    @Autowired OrderFulfilmentService fulfilment;
    @Autowired OrderProblemService problemService;
    @MockitoBean PaymentInternalClient payments;
    private final List<String> testOrderIds = new ArrayList<>();

    @BeforeEach void resetSweep() {
        sweep = new FulfilmentSweepService(orders, admin, fulfilment, Duration.ofDays(7), Duration.ofDays(14), 50);
    }

    @AfterEach void cleanTestCandidates() {
        for (String id : testOrderIds) jdbc.update("update orders set status='REFUNDED' where id=?", id);
    }

    @Test void pendingRefundsDoNotStarveFreshOrdersAndEachCohortAdvancesAndWraps() {
        Instant now = Instant.now();
        List<String> marked = new ArrayList<>();
        for (int i = 0; i < 51; i++) {
            String id = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(10)).plusSeconds(i));
            jdbc.update("update orders set refund_requested_at=? where id=?", java.sql.Timestamp.from(now), id);
            marked.add(id);
        }
        String fresh = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(8)));
        when(payments.refund(anyString(), any())).thenThrow(new IllegalStateException("unknown outcome"));
        assertThat(sweep.sweepShipBy(now)).isZero();
        assertThat(orders.findById(fresh).orElseThrow().getRefundRequestedAt()).isNotNull();
        verify(payments, times(51)).refund(anyString(), any());
        for (String id : marked.subList(0, 50)) verify(payments).refund(eq(id), any());
        verify(payments, never()).refund(eq(marked.getLast()), any());
        verify(payments).refund(eq(fresh), any());

        clearInvocations(payments);
        assertThat(sweep.sweepShipBy(now)).isZero();
        verify(payments).refund(eq(marked.getLast()), any());
        verify(payments).refund(eq(fresh), any());
        verify(payments, times(2)).refund(anyString(), any());

        clearInvocations(payments);
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            return new RefundResponse(id, "REFUND_PENDING");
        }).when(payments).refund(anyString(), any());
        assertThat(sweep.sweepShipBy(now)).isEqualTo(50);
        assertThat(sweep.sweepShipBy(now)).isEqualTo(2);
        for (String id : marked) {
            assertThat(orders.findById(id).orElseThrow().getRefundRequestedAt()).isNotNull();
            verify(payments).refund(eq(id), any());
        }
        verify(payments).refund(eq(fresh), any());
    }

    @Test void repeatedlyRefusedFreshOrdersDoNotStarveLaterDueOrders() {
        Instant now = Instant.now();
        for (int i = 0; i < 51; i++) seed("PAID", ListingType.HARDWARE,
                now.minus(Duration.ofDays(10)).plusSeconds(i));
        String later = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(8)));
        Request request = Request.create(Request.HttpMethod.POST, "http://payments/refund", java.util.Map.of(),
                new byte[0], java.nio.charset.StandardCharsets.UTF_8, null);
        when(payments.refund(anyString(), any())).thenThrow(new FeignException.Conflict(
                "ineligible", request, null, java.util.Map.of()));
        when(payments.refund(eq(later), any())).thenReturn(new RefundResponse(later, "REFUND_PENDING"));
        assertThat(sweep.sweepShipBy(now)).isZero();
        verify(payments, times(50)).refund(anyString(), any());
        assertThat(sweep.sweepShipBy(now)).isEqualTo(1);
        verify(payments).refund(eq(later), any());
        assertThat(orders.findById(later).orElseThrow().getRefundRequestedAt()).isNotNull();
        verify(payments, times(52)).refund(anyString(), any());
    }

    @Test void schedulerRunsOtherJobsWhileOneJobIsBlocked() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var blocked = scheduler.schedule(() -> {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("release timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, Instant.now());
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var other = scheduler.schedule(() -> {}, Instant.now());
            try { other.get(5, TimeUnit.SECONDS); } finally { other.cancel(true); }
        } finally {
            release.countDown();
            blocked.get(5, TimeUnit.SECONDS);
        }
    }

    @Test void concurrentInvocationsAdvanceTiedTimestampCandidatesWithoutRepeatingABatch() throws Exception {
        Instant now = Instant.now();
        for (int i = 0; i < 120; i++) seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(8)));
        Request request = Request.create(Request.HttpMethod.POST, "http://payments/refund", java.util.Map.of(),
                new byte[0], java.nio.charset.StandardCharsets.UTF_8, null);
        when(payments.refund(anyString(), any())).thenThrow(new FeignException.Conflict(
                "ineligible", request, null, java.util.Map.of()));
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return sweep.sweepShipBy(now); });
            var second = pool.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return sweep.sweepShipBy(now); });
            assertThat(first.get(30, TimeUnit.SECONDS)).isZero();
            assertThat(second.get(30, TimeUnit.SECONDS)).isZero();
        } finally { pool.shutdownNow(); }
        var attempted = mockingDetails(payments).getInvocations().stream()
                .map(invocation -> (String) invocation.getArgument(0)).toList();
        assertThat(attempted).hasSize(100).doesNotHaveDuplicates();
        clearInvocations(payments);
        assertThat(sweep.sweepShipBy(now)).isZero();
        verify(payments, times(20)).refund(anyString(), any());
    }

    @Test void onlyKnownOverdueHardwareIsMarkedAndRefunded() {
        Instant now = Instant.now();
        String due = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(8)));
        String recent = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(6)));
        String software = seed("PAID", ListingType.SOFTWARE, now.minus(Duration.ofDays(8)));
        String unknown = seed("PAID", ListingType.HARDWARE, null);
        when(payments.refund(eq(due), any())).thenReturn(new RefundResponse(due, "REFUND_PENDING"));
        assertThat(sweep.sweepShipBy(now)).isEqualTo(1);
        verify(payments).refund(eq(due), argThat(request -> "NOT_SHIPPED".equals(request.reason())));
        for (String id : new String[]{recent, software, unknown}) verify(payments, never()).refund(eq(id), any());
        assertThat(orders.findById(due).orElseThrow().getRefundRequestedAt()).isNotNull();
        assertThat(orders.findById(due).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThatThrownBy(() -> fulfilment.ship(due, "seller", false, Carrier.BRT, "123"))
                .isInstanceOf(OrderRefundPendingException.class);
    }

    @Test void shipmentWinsBeforeSweepClaimAndRefundMarkerBlocksDelivery() {
        String shipped = seed("SHIPPED", ListingType.HARDWARE, Instant.now().minus(Duration.ofDays(8)));
        assertThat(admin.refundUnshipped(shipped)).isFalse();
        verify(payments, never()).refund(eq(shipped), any());
        when(payments.refund(eq(shipped), any())).thenReturn(new RefundResponse(shipped, "REFUND_PENDING"));
        admin.refund(shipped, "ADMIN");
        assertThatThrownBy(() -> fulfilment.deliver(shipped, "buyer", false))
                .isInstanceOf(OrderRefundPendingException.class);
    }

    @Test void definitiveConflictClearsMarkerButUnknownOutcomeKeepsItAndSweepContinues() {
        Instant now = Instant.now();
        String conflict = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(10)));
        String unknown = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(9)));
        String success = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(8)));
        Request request = Request.create(Request.HttpMethod.POST, "http://payments/refund", java.util.Map.of(),
                new byte[0], java.nio.charset.StandardCharsets.UTF_8, null);
        when(payments.refund(eq(conflict), any())).thenThrow(new RuntimeException(
                new FeignException.Conflict("ineligible", request, null, java.util.Map.of())));
        when(payments.refund(eq(unknown), any())).thenThrow(new FeignException.ServiceUnavailable(
                "unavailable", request, null, java.util.Map.of()));
        when(payments.refund(eq(success), any())).thenReturn(new RefundResponse(success, "REFUND_PENDING"));
        assertThat(sweep.sweepShipBy(now)).isEqualTo(1);
        assertThat(orders.findById(conflict).orElseThrow().getRefundRequestedAt()).isNull();
        assertThat(orders.findById(unknown).orElseThrow().getRefundRequestedAt()).isNotNull();
        verify(payments).refund(eq(success), any());
    }

    @Test void overdueShipmentsAutoDeliverOnceAndRespectProblemAndRefundPauses() {
        Instant now = Instant.now();
        String overdue = seedShipped(now, Duration.ofDays(15));
        String recent = seedShipped(now, Duration.ofDays(13));
        String open = seedShipped(now, Duration.ofDays(15));
        String pending = seedShipped(now, Duration.ofDays(15));
        String withdraw = seedShipped(now, Duration.ofDays(15));
        String dismissed = seedShipped(now, Duration.ofDays(15));
        String refundPending = seedShipped(now, Duration.ofDays(15));

        problemService.open(open, "buyer", false, new ReportProblemRequest(ProblemReason.OTHER, "Open issue"));
        problemService.open(pending, "buyer", false, new ReportProblemRequest(ProblemReason.OTHER, "Pending issue"));
        jdbc.update("update order_problem set status='REFUND_PENDING' where order_id=?", pending);
        problemService.open(withdraw, "buyer", false, new ReportProblemRequest(ProblemReason.OTHER, "Withdrawn issue"));
        problemService.withdraw(withdraw, "buyer", false);
        problemService.open(dismissed, "buyer", false, new ReportProblemRequest(ProblemReason.OTHER, "Dismissed issue"));
        jdbc.update("update order_problem set status='RESOLVED', outcome='DISMISS' where order_id=?", dismissed);
        jdbc.update("update orders set refund_requested_at=? where id=?", java.sql.Timestamp.from(now), refundPending);

        when(payments.refund(anyString(), any())).thenAnswer(invocation ->
                new RefundResponse(invocation.getArgument(0), "REFUND_PENDING"));
        String paidWithProblem = seed("PAID", ListingType.HARDWARE, now.minus(Duration.ofDays(8)));
        problemService.open(paidWithProblem, "buyer", false,
                new ReportProblemRequest(ProblemReason.OTHER, "Late delivery report"));

        assertThat(sweep.sweepAutoDeliver(now)).isEqualTo(3);
        assertThat(orders.findById(overdue).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(orders.findById(recent).orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        for (String id : List.of(open, pending, refundPending)) {
            assertThat(orders.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        }
        assertThat(orders.findById(withdraw).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(orders.findById(dismissed).orElseThrow().getStatus()).isEqualTo(OrderStatus.DELIVERED);
        assertThat(deliveredEvents(overdue)).isEqualTo(1L);
        assertThat(sweep.sweepAutoDeliver(now.plusSeconds(1))).isZero();
        assertThat(deliveredEvents(overdue)).isEqualTo(1L);

        assertThat(sweep.sweepShipBy(now)).isEqualTo(1);
        verify(payments).refund(eq(paidWithProblem), argThat(request -> "NOT_SHIPPED".equals(request.reason())));
    }

    private String seedShipped(Instant now, Duration age) {
        String id = seed("SHIPPED", ListingType.HARDWARE, now.minus(Duration.ofDays(1)));
        jdbc.update("update orders set shipped_at=? where id=?", java.sql.Timestamp.from(now.minus(age)), id);
        return id;
    }

    private long deliveredEvents(String id) {
        return jdbc.queryForObject("select count(*) from order_outbox_events where aggregate_id=? and event_type=?",
                Long.class, id, "order.delivered");
    }

    private String seed(String status, ListingType type, Instant paidAt) {
        String id = UUID.randomUUID().toString();
        testOrderIds.add(id);
        orders.saveAndFlush(new Order(id, "buyer", "seller", UUID.randomUUID().toString(), type,
                "Private title", BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.TEN, PaymentMethod.PAYPAL,
                id, UUID.randomUUID().toString(), Instant.now(), Instant.now().plusSeconds(1800)));
        jdbc.update("update orders set status=?, paid_at=? where id=?", status,
                paidAt == null ? null : java.sql.Timestamp.from(paidAt), id);
        return id;
    }
}
