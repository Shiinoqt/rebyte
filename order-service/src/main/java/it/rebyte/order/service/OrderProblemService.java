package it.rebyte.order.service;

import java.time.Instant;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.ProblemStatus;
import it.rebyte.order.dto.ProblemView;
import it.rebyte.order.dto.ReportProblemRequest;
import it.rebyte.order.error.OrderActionForbiddenException;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.error.ProblemAlreadyReportedException;
import it.rebyte.order.error.ProblemNotFoundException;
import it.rebyte.order.error.ProblemNotOpenException;
import it.rebyte.order.error.ProblemNotReportableException;
import it.rebyte.order.messaging.ProblemEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OrderProblemRepository;
import it.rebyte.order.repository.OrderRepository;

@Service
public class OrderProblemService {

    private final OrderRepository orders;
    private final OrderProblemRepository problems;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;
    private final Duration reportWindow;

    public OrderProblemService(OrderRepository orders, OrderProblemRepository problems, DomainEventRecorder events,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.fulfilment.report-window:P14D}") Duration reportWindow) {
        this.orders = orders;
        this.problems = problems;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.reportWindow = reportWindow;
    }

    public ProblemView open(String orderId, String callerSub, boolean admin, ReportProblemRequest request) {
        try {
            return transactions.execute(status -> openInTransaction(orderId, callerSub, admin, request));
        } catch (DataIntegrityViolationException duplicate) {
            Throwable cause = duplicate.getMostSpecificCause();
            if (cause != null && cause.getMessage() != null
                    && cause.getMessage().contains("uk_order_problem_order")) {
                throw new ProblemAlreadyReportedException();
            }
            throw duplicate;
        }
    }

    private ProblemView openInTransaction(String orderId, String callerSub, boolean admin,
            ReportProblemRequest request) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        authorizeBuyer(order, callerSub, admin);
        if (problems.existsByOrderId(orderId)) {
            throw new ProblemAlreadyReportedException();
        }
        if (!reportable(order, Instant.now())) {
            throw new ProblemNotReportableException();
        }
        OrderProblem problem = problems.saveAndFlush(new OrderProblem(UUID.randomUUID().toString(), orderId,
                request.reason(), request.description(), Instant.now()));
        events.record(RebyteExchanges.ORDER_PROBLEM_OPENED, orderId, ProblemEvent.of(problem, order));
        return ProblemView.from(problem);
    }

    public ProblemView withdraw(String orderId, String callerSub, boolean admin) {
        return transactions.execute(status -> withdrawInTransaction(orderId, callerSub, admin));
    }

    private ProblemView withdrawInTransaction(String orderId, String callerSub, boolean admin) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        authorizeBuyer(order, callerSub, admin);
        OrderProblem problem = problems.findByOrderId(orderId).orElseThrow(ProblemNotFoundException::new);
        int updated = problems.withdraw(problem.getId(), Instant.now(), ProblemStatus.OPEN, ProblemStatus.WITHDRAWN);
        if (updated == 0) {
            throw new ProblemNotOpenException();
        }
        return problems.findByOrderId(orderId).map(ProblemView::from).orElseThrow(ProblemNotFoundException::new);
    }

    private boolean reportable(Order order, Instant now) {
        return switch (order.getStatus()) {
            case PAID, SHIPPED -> true;
            case DELIVERED -> order.getDeliveredAt() != null
                    && now.isBefore(order.getDeliveredAt().plus(reportWindow));
            case AWAITING_PAYMENT, CANCELLED, REFUNDED -> false;
        };
    }

    private void authorizeBuyer(Order order, String callerSub, boolean admin) {
        if (callerSub.equals(order.getBuyerSub())) {
            return;
        }
        if (admin || callerSub.equals(order.getSellerSub())) {
            throw new OrderActionForbiddenException();
        }
        throw new OrderNotFoundException(order.getId());
    }
}
