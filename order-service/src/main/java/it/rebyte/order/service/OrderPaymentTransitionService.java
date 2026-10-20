package it.rebyte.order.service;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.License;
import it.rebyte.order.domain.LicenseStatus;
import it.rebyte.order.messaging.LicenseEvent;
import it.rebyte.order.messaging.OrderFulfilmentEvent;
import it.rebyte.order.messaging.PaymentResultEvent;
import it.rebyte.order.messaging.OrderRefundedEvent;
import it.rebyte.order.messaging.ProblemEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.LicenseRepository;
import it.rebyte.order.repository.OrderProblemRepository;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.domain.ProblemOutcome;
import it.rebyte.order.domain.ProblemStatus;

@Service
public class OrderPaymentTransitionService {

    private final JdbcTemplate jdbc;
    private final OrderRepository orders;
    private final DomainEventRecorder events;
    private final OfferHoldService offerHolds;
    private final LicenseRepository licenses;
    private final LicenseService licenseService;
    private final OrderProblemRepository problems;

    public OrderPaymentTransitionService(JdbcTemplate jdbc, OrderRepository orders, DomainEventRecorder events,
            OfferHoldService offerHolds, LicenseRepository licenses, LicenseService licenseService,
            OrderProblemRepository problems) {
        this.jdbc = jdbc;
        this.orders = orders;
        this.events = events;
        this.offerHolds = offerHolds;
        this.licenses = licenses;
        this.licenseService = licenseService;
        this.problems = problems;
    }

    @Transactional
    public void apply(String eventType, PaymentResultEvent result) {
        if (result.eventId() == null || result.eventId().isBlank() || result.eventId().length() > 100
                || result.orderId() == null || result.orderId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid payment event identity");
        }
        int inserted = jdbc.update("INSERT IGNORE INTO order_inbox_events (event_id, received_at) VALUES (?, ?)",
                result.eventId(), java.sql.Timestamp.from(Instant.now()));
        if (inserted == 0) {
            return;
        }
        Order order = orders.findByIdForUpdate(result.orderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown order"));
        // An order cancelled while its payment create was still in flight never stored the
        // provider order id; a later no-charge outcome for it has nothing left to change.
        if (order.getStatus() == OrderStatus.CANCELLED
                && ("payment.expired".equals(eventType) || "payment.failed".equals(eventType))) {
            return;
        }
        if (order.getProviderOrderId() == null || !order.getProviderOrderId().equals(result.providerOrderId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Payment provider order mismatch");
        }
        switch (eventType) {
            case "payment.succeeded" -> paid(order, result);
            case "payment.failed" -> cancel(order, result, CancellationReason.PAYMENT_DECLINED);
            case "payment.expired" -> cancel(order, result, CancellationReason.TIMEOUT);
            case "payment.refunded" -> refunded(order, result);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown payment event type");
        }
    }

    private void paid(Order order, PaymentResultEvent result) {
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.SHIPPED
                || order.getStatus() == OrderStatus.DELIVERED) {
            return;
        }
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT
                || order.getCheckoutPhase() != CheckoutPhase.CAPTURE_AUTHORIZED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment success conflicts with order state");
        }
        if (result.providerCaptureId() == null || result.providerCaptureId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Missing capture correlation");
        }
        Instant now = Instant.now();
        order.markPaid(result.providerCaptureId(), now);
        events.record(RebyteExchanges.ORDER_PAID, order);
        if (order.getListingType() == ListingType.SOFTWARE) {
            order.markDelivered(now);
            License license = licenseService.issue(order, now);
            events.record(RebyteExchanges.ORDER_DELIVERED, order.getId(), OrderFulfilmentEvent.from(order));
            events.record(RebyteExchanges.LICENCE_ISSUED, order.getId(), LicenseEvent.of(license, now));
        }
    }

    private void cancel(Order order, PaymentResultEvent result, CancellationReason reason) {
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return;
        }
        if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Payment failure conflicts with order state");
        }
        if (order.getCheckoutPhase() == CheckoutPhase.CAPTURE_AUTHORIZED
                && (!result.terminalNoCharge() || result.captureOperationId() == null
                    || !result.captureOperationId().equals(order.getCaptureOperationId()))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Capture outcome is not terminal no-charge");
        }
        order.markCancelled(reason);
        offerHolds.onOrderCancelled(order);
        events.record("order.cancelled", order);
    }

    private void refunded(Order order, PaymentResultEvent result) {
        if (order.getStatus() == OrderStatus.REFUNDED) {
            return;
        }
        if (order.getStatus() != OrderStatus.PAID && order.getStatus() != OrderStatus.SHIPPED
                && order.getStatus() != OrderStatus.DELIVERED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Refund conflicts with order state");
        }
        if (order.getProviderCaptureId() == null
                || !order.getProviderCaptureId().equals(result.providerCaptureId())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Refund capture mismatch");
        }
        OrderStatus refundedFrom = order.getStatus();
        order.markRefunded();
        events.record(RebyteExchanges.ORDER_REFUNDED, order.getId(), OrderRefundedEvent.of(order, refundedFrom));
        Instant now = Instant.now();
        if (order.getListingType() == ListingType.SOFTWARE) {
            LicenseEvent revoked = LicenseEvent.of(order, LicenseStatus.REVOKED, now);
            if (licenses.revokeForOrder(order.getId(), now) == 1) {
                events.record(RebyteExchanges.LICENCE_REVOKED, order.getId(), revoked);
            }
        }
        if (problems.resolveByRefund(order.getId(), now,
                java.util.List.of(ProblemStatus.OPEN, ProblemStatus.REFUND_PENDING), ProblemStatus.RESOLVED,
                ProblemOutcome.REFUND) == 1) {
            problems.findByOrderId(order.getId()).ifPresent(problem -> events.record(
                    RebyteExchanges.ORDER_PROBLEM_RESOLVED, order.getId(), ProblemEvent.of(problem, order)));
        }
    }
}
