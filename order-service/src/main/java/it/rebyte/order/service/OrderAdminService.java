package it.rebyte.order.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import it.rebyte.order.client.FeignErrors;
import it.rebyte.order.client.PaymentInternalClient;
import it.rebyte.order.config.RabbitConfig;
import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.dto.PaymentStatusResponse;
import it.rebyte.order.dto.RefundRequest;
import it.rebyte.order.dto.RefundResponse;
import it.rebyte.order.dto.StuckOrderPage;
import it.rebyte.order.error.InvalidOrderCursorException;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.error.RefundNotEligibleException;
import it.rebyte.order.error.RefundUnavailableException;
import it.rebyte.order.repository.OrderRepository;
import io.micrometer.core.instrument.MeterRegistry;

@Service
public class OrderAdminService {

    private static final Logger log = LoggerFactory.getLogger(OrderAdminService.class);
    private static final int METRICS_BATCH_SIZE = 100;
    private static final List<String> STUCK_CATEGORIES =
            List.of("awaiting_timeout", "captured_awaiting_webhook", "failed_create");

    private final OrderRepository orders;
    private final PaymentInternalClient payments;
    private final RabbitTemplate rabbit;
    private volatile Map<String, Double> stuckCounts = Map.of();
    private Map<String, Double> pendingCounts = new HashMap<>();
    private Cursor metricsCursor = new Cursor(null, null);
    private Instant metricsCutoff;

    public OrderAdminService(OrderRepository orders, PaymentInternalClient payments, RabbitTemplate rabbit,
            MeterRegistry meters) {
        this.orders = orders;
        this.payments = payments;
        this.rabbit = rabbit;
        for (String category : STUCK_CATEGORIES) {
            meters.gauge("rebyte.order.stuck", List.of(io.micrometer.core.instrument.Tag.of("category", category)),
                    this, service -> service.countCategory(category));
        }
        meters.gauge("rebyte.order.saga.dlq.depth", this, service -> service.dlqDepth());
    }

    public RefundResponse refund(String orderId, String reason) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.SHIPPED
                && order.getStatus() != OrderStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not eligible for refund");
        }
        if (orders.markRefundRequested(orderId, Instant.now(),
                List.of(OrderStatus.PAID, OrderStatus.SHIPPED, OrderStatus.DELIVERED)) == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order is not eligible for refund");
        }
        return requestRefund(orderId, reason);
    }

    public boolean refundUnshipped(String orderId) {
        if (orders.markRefundRequested(orderId, Instant.now(), List.of(OrderStatus.PAID)) == 0) return false;
        requestRefund(orderId, "NOT_SHIPPED");
        return true;
    }

    private RefundResponse requestRefund(String orderId, String reason) {
        // The marker commits before the remote call so no database lock spans PayPal work.
        try {
            return payments.refund(orderId, new RefundRequest(reason));
        } catch (RuntimeException failure) {
            if (FeignErrors.isConflict(failure)) {
                orders.clearRefundRequested(orderId);
                throw new RefundNotEligibleException();
            }
            throw new RefundUnavailableException(orderId, failure);
        }
    }

    public StuckOrderPage stuck(String cursor, Integer requestedSize) {
        int size = requestedSize == null ? 20 : requestedSize;
        if (size < 1 || size > 50) {
            throw new InvalidOrderCursorException();
        }
        Cursor before = decode(cursor);
        Instant now = Instant.now();
        List<Order> candidates = orders.adminStuckCandidates(now, before.createdAt(), before.id(),
                PageRequest.of(0, size + 1));
        List<StuckOrderPage.Item> items = new ArrayList<>();
        for (Order order : candidates.subList(0, Math.min(candidates.size(), size))) {
            StuckOrderPage.Item item = classify(order, now);
            if (item != null) {
                items.add(item);
            }
        }
        String next = candidates.size() > size ? encode(candidates.get(size - 1)) : null;
        return new StuckOrderPage(List.copyOf(items), next);
    }

    private StuckOrderPage.Item classify(Order order, Instant now) {
        PaymentStatusResponse payment;
        try {
            payment = payments.status(order.getId());
        } catch (RuntimeException failure) {
            if (!FeignErrors.isNotFound(failure)) {
                throw failure;
            }
            payment = null;
        }
        String category = null;
        if (order.getCancellationReason() == CancellationReason.PAYMENT_UNAVAILABLE
                || (order.getCheckoutPhase() == CheckoutPhase.CREATING && order.getExpiryAt().isBefore(now))) {
            category = "failed_create";
        } else if (order.getCheckoutPhase() == CheckoutPhase.CAPTURE_AUTHORIZED && payment != null
                && payment.capturedAwaitingWebhook()) {
            category = "captured_awaiting_webhook";
        } else if (order.getStatus() == OrderStatus.AWAITING_PAYMENT && order.getExpiryAt().isBefore(now)) {
            category = "awaiting_timeout";
        }
        return category == null ? null : new StuckOrderPage.Item(order.getId(), category,
                order.getCreatedAt(), order.getExpiryAt(),
                order.getCancellationReason() == null ? null : order.getCancellationReason().name(),
                payment == null ? null : payment.lastPayPalStatus());
    }

    private double countCategory(String category) {
        return stuckCounts.getOrDefault(category, Double.NaN);
    }

    @Scheduled(fixedDelayString = "${rebyte.order.stuck-metrics.fixed-delay-ms:30000}")
    public synchronized void refreshStuckCounts() {
        if (metricsCutoff == null) {
            metricsCutoff = Instant.now();
        }
        try {
            List<Order> batch = orders.adminStuckCandidates(metricsCutoff,
                    metricsCursor.createdAt(), metricsCursor.id(), PageRequest.of(0, METRICS_BATCH_SIZE));
            Map<String, Double> counts = new HashMap<>(pendingCounts);
            for (Order order : batch) {
                StuckOrderPage.Item item = classify(order, metricsCutoff);
                if (item != null) {
                    counts.merge(item.category(), 1.0, Double::sum);
                }
            }
            if (batch.size() < METRICS_BATCH_SIZE) {
                STUCK_CATEGORIES.forEach(category -> counts.putIfAbsent(category, 0.0));
                // Publish only complete scans; a failed page must not corrupt the last snapshot.
                stuckCounts = Map.copyOf(counts);
                pendingCounts = new HashMap<>();
                metricsCursor = new Cursor(null, null);
                metricsCutoff = null;
            } else {
                Order last = batch.getLast();
                pendingCounts = counts;
                metricsCursor = new Cursor(last.getCreatedAt(), last.getId());
            }
        } catch (RuntimeException unavailable) {
            log.warn("stuck-order metrics refresh failed; retaining previous snapshot and retrying this page", unavailable);
        }
    }

    private double dlqDepth() {
        try {
            Long payment = rabbit.execute(channel -> channel.messageCount(RabbitConfig.PAYMENT_RESULT_DLQ));
            Long listing = rabbit.execute(channel -> channel.messageCount(RabbitConfig.LISTING_LIFECYCLE_DLQ));
            if (payment == null || listing == null) {
                return Double.NaN;
            }
            return payment.doubleValue() + listing.doubleValue();
        } catch (RuntimeException unavailable) {
            return Double.NaN;
        }
    }

    private Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidOrderCursorException();
            }
            return new Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidOrderCursorException();
        }
    }

    private String encode(Order order) {
        String value = order.getCreatedAt() + "|" + order.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(Instant createdAt, String id) {
    }
}
