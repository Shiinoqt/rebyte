package it.rebyte.order.service;

import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.*;
import it.rebyte.order.error.*;
import it.rebyte.order.messaging.OrderFulfilmentEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.repository.OrderProblemRepository;

@Service
public class OrderFulfilmentService {
    private final OrderRepository orders;
    private final OrderProblemRepository problems;
    private final DomainEventRecorder events;
    public OrderFulfilmentService(OrderRepository orders, OrderProblemRepository problems, DomainEventRecorder events) {
        this.orders = orders; this.problems = problems; this.events = events;
    }
    @Transactional
    public void ship(String id, String sub, boolean admin, Carrier carrier, String trackingCode) {
        check(id, sub, admin, OrderStatus.PAID, false);
        if (orders.markShipped(id, Instant.now(), carrier, trackingCode) == 0) throw new OrderTransitionConflictException();
        record(id, RebyteExchanges.ORDER_SHIPPED);
    }
    @Transactional
    public void deliver(String id, String sub, boolean admin) {
        check(id, sub, admin, OrderStatus.SHIPPED, true);
        if (orders.markDelivered(id, Instant.now()) == 0) throw new OrderTransitionConflictException();
        record(id, RebyteExchanges.ORDER_DELIVERED);
    }
    @Transactional
    public boolean autoDeliver(String id, Instant cutoff, Instant now) {
        Order order = orders.findByIdForUpdate(id).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.SHIPPED || order.getShippedAt() == null
                || !order.getShippedAt().isBefore(cutoff) || order.getRefundRequestedAt() != null
                || problems.existsByOrderIdAndStatusIn(id, List.of(ProblemStatus.OPEN, ProblemStatus.REFUND_PENDING))) {
            return false;
        }
        if (orders.markDelivered(id, now) == 0) {
            return false;
        }
        record(id, RebyteExchanges.ORDER_DELIVERED);
        return true;
    }
    private void check(String id, String sub, boolean admin, OrderStatus from, boolean buyerAction) {
        Order order = orders.findByIdForUpdate(id).orElseThrow(() -> new OrderNotFoundException(id));
        if (!sub.equals(order.getBuyerSub()) && !sub.equals(order.getSellerSub()) && !admin)
            throw new OrderNotFoundException(id);
        if (buyerAction && !sub.equals(order.getBuyerSub())) throw new OrderActionForbiddenException();
        if (!buyerAction && !sub.equals(order.getSellerSub())) throw new OrderActionForbiddenException();
        if (order.getRefundRequestedAt() != null) throw new OrderRefundPendingException();
        if (order.getListingType() != ListingType.HARDWARE || order.getStatus() != from)
            throw new OrderTransitionConflictException();
    }
    private void record(String id, String eventType) {
        Order order = orders.findById(id).orElseThrow(() -> new OrderNotFoundException(id));
        events.record(eventType, id, OrderFulfilmentEvent.from(order));
    }
}
