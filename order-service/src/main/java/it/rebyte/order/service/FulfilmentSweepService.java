package it.rebyte.order.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.HashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import it.rebyte.order.domain.Order;
import it.rebyte.order.error.RefundNotEligibleException;
import it.rebyte.order.error.RefundUnavailableException;
import it.rebyte.order.repository.OrderRepository;

@Service
public class FulfilmentSweepService {
    private static final Logger log = LoggerFactory.getLogger(FulfilmentSweepService.class);
    private final OrderRepository orders;
    private final OrderAdminService admin;
    private final Duration shipByDeadline;
    private final Duration autoDeliverAfter;
    private final int batchSize;
    private Cursor freshCursor = new Cursor(null, null);
    private Cursor retryCursor = new Cursor(null, null);

    public FulfilmentSweepService(OrderRepository orders, OrderAdminService admin, OrderFulfilmentService fulfilment,
            @Value("${rebyte.fulfilment.ship-by-deadline:P7D}") Duration shipByDeadline,
            @Value("${rebyte.fulfilment.auto-deliver-after:P14D}") Duration autoDeliverAfter,
            @Value("${rebyte.fulfilment.sweep-batch-size:50}") int batchSize) {
        this.orders = orders; this.admin = admin; this.fulfilment = fulfilment;
        this.shipByDeadline = shipByDeadline; this.autoDeliverAfter = autoDeliverAfter; this.batchSize = batchSize;
    }
    private final OrderFulfilmentService fulfilment;
    public synchronized int sweepShipBy(Instant now) {
        Instant cutoff = now.minus(shipByDeadline);
        // Snapshot both cohorts before refund calls can move fresh orders into the retry cohort.
        List<Order> fresh = shipByBatch(cutoff, false, freshCursor);
        List<Order> retries = shipByBatch(cutoff, true, retryCursor);
        freshCursor = advance(fresh);
        retryCursor = advance(retries);
        var attempted = new HashSet<String>();
        int count = 0;
        for (Order order : java.util.stream.Stream.concat(fresh.stream(), retries.stream()).toList()) {
            if (!attempted.add(order.getId())) continue;
            try {
                if (admin.refundUnshipped(order.getId())) count++;
            } catch (RefundNotEligibleException failure) {
                log.warn("Order {} refund-not-eligible", order.getId());
            } catch (RefundUnavailableException failure) {
                log.warn("Order {} refund-unavailable", order.getId());
            }
        }
        return count;
    }

    private List<Order> shipByBatch(Instant cutoff, boolean retry, Cursor after) {
        List<Order> batch = orders.shipByCandidates(cutoff, retry, after.paidAt(), after.id(),
                PageRequest.of(0, batchSize));
        if (batch.isEmpty() && after.paidAt() != null) {
            batch = orders.shipByCandidates(cutoff, retry, null, null, PageRequest.of(0, batchSize));
        }
        return batch;
    }

    private Cursor advance(List<Order> batch) {
        if (batch.isEmpty()) return new Cursor(null, null);
        Order last = batch.getLast();
        return new Cursor(last.getPaidAt(), last.getId());
    }

    private record Cursor(Instant paidAt, String id) {}

    public int sweepAutoDeliver(Instant now) {
        int count = 0;
        Instant cutoff = now.minus(autoDeliverAfter);
        for (Order order : orders.autoDeliverCandidates(cutoff, PageRequest.of(0, batchSize))) {
            try {
                if (fulfilment.autoDeliver(order.getId(), cutoff, now)) count++;
            } catch (RuntimeException failure) {
                log.warn("Order {} automatic delivery failed", order.getId(), failure);
            }
        }
        return count;
    }
    @Scheduled(fixedDelayString = "${rebyte.fulfilment.sweep-delay-ms:60000}",
            initialDelayString = "${rebyte.fulfilment.sweep-initial-delay-ms:60000}")
    public void refundUnshippedOrders() { sweepShipBy(Instant.now()); }

    @Scheduled(fixedDelayString = "${rebyte.fulfilment.sweep-delay-ms:60000}",
            initialDelayString = "${rebyte.fulfilment.sweep-initial-delay-ms:60000}")
    public void deliverOverdueShipments() { sweepAutoDeliver(Instant.now()); }
}
