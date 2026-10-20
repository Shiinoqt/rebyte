package it.rebyte.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import it.rebyte.order.client.PaymentInternalClient;
import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.dto.PaymentStatusResponse;
import it.rebyte.order.repository.OrderRepository;

class OrderAdminMetricsTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentInternalClient payments = mock(PaymentInternalClient.class);
    private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OrderAdminService service = new OrderAdminService(orders, payments, rabbit, meters);
    private final Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void metricReadsBeforeFirstCompleteScanAreUnknownAndDoNoIo() {
        for (int read = 0; read < 3; read++) {
            assertThat(value("awaiting_timeout")).isNaN();
            assertThat(value("captured_awaiting_webhook")).isNaN();
            assertThat(value("failed_create")).isNaN();
        }
        verifyNoInteractions(orders, payments, rabbit);
    }

    @Test
    void categoriesShareOneBackgroundScanAndMetricReadsNeverRepeatRemoteCalls() {
        Order timeout = order("timeout", CheckoutPhase.READY, null);
        Order captured = order("captured", CheckoutPhase.CAPTURE_AUTHORIZED, null);
        Order failed = order("failed", CheckoutPhase.CANCELLED, CancellationReason.PAYMENT_UNAVAILABLE);
        when(orders.adminStuckCandidates(any(), isNull(), isNull(), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(timeout, captured, failed)).thenReturn(List.of());
        when(payments.status("captured")).thenReturn(new PaymentStatusResponse("captured", "CAPTURING",
                "provider", "COMPLETED", true));

        service.refreshStuckCounts();
        verify(orders).adminStuckCandidates(any(), isNull(), isNull(), eq(PageRequest.of(0, 100)));
        verify(payments).status("timeout");
        verify(payments).status("captured");
        verify(payments).status("failed");
        clearInvocations(orders, payments, rabbit);

        for (int read = 0; read < 3; read++) {
            assertCounts(1, 1, 1);
        }
        verifyNoInteractions(orders, payments, rabbit);

        service.refreshStuckCounts();
        assertCounts(0, 0, 0);
    }

    @Test
    void boundedPagesUseStableCursorAndPublishOnlyTheCompletedScan() {
        Order previous = order("previous", CheckoutPhase.READY, null);
        List<Order> firstPage = IntStream.range(0, 100)
                .mapToObj(i -> order("failed-" + (100 - i), CheckoutPhase.CANCELLED,
                        CancellationReason.PAYMENT_UNAVAILABLE)).toList();
        String lastId = firstPage.getLast().getId();
        Order tail = order("tail", CheckoutPhase.CAPTURE_AUTHORIZED, null);
        when(payments.status("tail")).thenReturn(new PaymentStatusResponse("tail", "CAPTURING", null, null, true));
        when(orders.adminStuckCandidates(any(), isNull(), isNull(), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(previous)).thenReturn(firstPage);
        when(orders.adminStuckCandidates(any(), eq(createdAt), eq(lastId), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(tail));
        service.refreshStuckCounts();
        assertCounts(1, 0, 0);
        clearInvocations(orders, payments);

        service.refreshStuckCounts();
        assertCounts(1, 0, 0);
        verify(payments, times(100)).status(any());
        verify(orders).adminStuckCandidates(any(), isNull(), isNull(), eq(PageRequest.of(0, 100)));

        service.refreshStuckCounts();
        assertCounts(0, 1, 100);
        ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
        verify(orders).adminStuckCandidates(cutoff.capture(), isNull(), isNull(), eq(PageRequest.of(0, 100)));
        verify(orders).adminStuckCandidates(eq(cutoff.getValue()), eq(createdAt), eq(lastId),
                eq(PageRequest.of(0, 100)));
        verify(payments, times(101)).status(any());
    }

    @Test
    void failedPageKeepsPreviousSnapshotAndRetriesWithoutCountingSuccessfulRowsTwice() {
        Order previous = order("previous", CheckoutPhase.CANCELLED, CancellationReason.PAYMENT_UNAVAILABLE);
        Order first = order("first", CheckoutPhase.READY, null);
        Order second = order("second", CheckoutPhase.READY, null);
        when(orders.adminStuckCandidates(any(), isNull(), isNull(), eq(PageRequest.of(0, 100))))
                .thenReturn(List.of(previous)).thenReturn(List.of(first, second));
        when(payments.status("second")).thenThrow(new IllegalStateException("payment unavailable")).thenReturn(null);
        service.refreshStuckCounts();
        assertCounts(0, 0, 1);

        service.refreshStuckCounts();
        assertCounts(0, 0, 1);
        service.refreshStuckCounts();
        assertCounts(2, 0, 0);
        verify(payments, times(2)).status("first");
    }

    private Order order(String id, CheckoutPhase phase, CancellationReason reason) {
        Order order = mock(Order.class);
        when(order.getId()).thenReturn(id);
        when(order.getCreatedAt()).thenReturn(createdAt);
        when(order.getExpiryAt()).thenReturn(createdAt.plusSeconds(3600));
        when(order.getStatus()).thenReturn(OrderStatus.AWAITING_PAYMENT);
        when(order.getCheckoutPhase()).thenReturn(phase);
        when(order.getCancellationReason()).thenReturn(reason);
        return order;
    }

    private double value(String category) {
        return meters.get("rebyte.order.stuck").tag("category", category).gauge().value();
    }

    private void assertCounts(double timeout, double captured, double failed) {
        assertThat(value("awaiting_timeout")).isEqualTo(timeout);
        assertThat(value("captured_awaiting_webhook")).isEqualTo(captured);
        assertThat(value("failed_create")).isEqualTo(failed);
    }
}
