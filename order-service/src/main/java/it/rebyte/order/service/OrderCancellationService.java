package it.rebyte.order.service;

import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.error.OrderCancellationConflictException;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OrderRepository;

@Service
public class OrderCancellationService {

    private static final List<CheckoutPhase> CANCELLABLE = List.of(CheckoutPhase.CREATING, CheckoutPhase.READY);

    private final OrderRepository orders;
    private final DomainEventRecorder events;
    private final OfferHoldService offerHolds;
    private final TransactionTemplate transactions;

    public OrderCancellationService(OrderRepository orders, DomainEventRecorder events, OfferHoldService offerHolds,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.events = events;
        this.offerHolds = offerHolds;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public void cancelByBuyer(String orderId, String buyerSub) {
        Order order = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!buyerSub.equals(order.getBuyerSub())) {
            throw new OrderNotFoundException(orderId);
        }
        if (!cancel(orderId, CancellationReason.BUYER_ABORTED)) {
            throw new OrderCancellationConflictException(orderId);
        }
    }

    public boolean cancel(String orderId, CancellationReason reason) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            int changed = orders.cancelFromPhases(orderId, OrderStatus.CANCELLED, CANCELLABLE,
                    CheckoutPhase.CANCELLED, reason);
            if (changed == 0) {
                return false;
            }
            Order cancelled = orders.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            offerHolds.onOrderCancelled(cancelled);
            events.record("order.cancelled", cancelled);
            return true;
        }));
    }

    @Scheduled(fixedDelayString = "${rebyte.checkout.create-recovery-delay-ms:60000}")
    public void recoverExpiredCreation() {
        for (Order order : orders.expiredCreating(Instant.now(), org.springframework.data.domain.PageRequest.of(0, 50))) {
            cancel(order.getId(), CancellationReason.TIMEOUT);
        }
    }
}
