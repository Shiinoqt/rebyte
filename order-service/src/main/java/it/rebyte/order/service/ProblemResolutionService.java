package it.rebyte.order.service;

import java.time.Instant;
import java.time.DateTimeException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderProblem;
import it.rebyte.order.domain.ProblemStatus;
import it.rebyte.order.domain.ProblemOutcome;
import it.rebyte.order.dto.DismissProblemRequest;
import it.rebyte.order.dto.AdminProblemPage;
import it.rebyte.order.dto.ProblemView;
import it.rebyte.order.error.OrderActionForbiddenException;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.error.InvalidProblemFilterException;
import it.rebyte.order.error.ProblemNotFoundException;
import it.rebyte.order.error.ProblemNotOpenException;
import it.rebyte.order.error.RefundNotEligibleException;
import it.rebyte.order.messaging.ProblemEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OrderProblemRepository;
import it.rebyte.order.repository.OrderRepository;

@Service
public class ProblemResolutionService {

    private static final String REFUND_REASON = "PROBLEM_REFUND";

    private final OrderRepository orders;
    private final OrderProblemRepository problems;
    private final OrderAdminService admin;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public ProblemResolutionService(OrderRepository orders, OrderProblemRepository problems,
            OrderAdminService admin, DomainEventRecorder events, PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.problems = problems;
        this.admin = admin;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ProblemView sellerRefund(String orderId, String callerSub, String note) {
        Claim claim = transactions.execute(status -> {
            Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            if (!callerSub.equals(order.getSellerSub())) {
                if (callerSub.equals(order.getBuyerSub())) {
                    throw new OrderActionForbiddenException();
                }
                throw new OrderNotFoundException(orderId);
            }
            OrderProblem problem = problems.findByOrderId(orderId).orElseThrow(ProblemNotFoundException::new);
            return claimRefund(problem, callerSub, note);
        });
        requestRefund(claim);
        return problems.findByOrderId(orderId).map(ProblemView::from).orElseThrow(ProblemNotFoundException::new);
    }

    public ProblemView refundByAdmin(String problemId, String adminSub, String note) {
        Claim claim = transactions.execute(status -> {
            String orderId = problems.findOrderIdById(problemId).orElseThrow(ProblemNotFoundException::new);
            orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            // A locking read bypasses the REPEATABLE READ snapshot opened by the order-id lookup.
            OrderProblem lockedProblem = problems.findByIdForUpdate(problemId).orElseThrow(ProblemNotFoundException::new);
            return claimRefund(lockedProblem, adminSub, note);
        });
        requestRefund(claim);
        return problems.findById(claim.problemId()).map(ProblemView::from).orElseThrow(ProblemNotFoundException::new);
    }

    public ProblemView dismissByAdmin(String problemId, String adminSub, DismissProblemRequest request) {
        return transactions.execute(status -> {
            String orderId = problems.findOrderIdById(problemId).orElseThrow(ProblemNotFoundException::new);
            Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
            int updated = problems.dismiss(problemId, adminSub, request.note(), Instant.now(), ProblemStatus.OPEN,
                    ProblemStatus.RESOLVED, ProblemOutcome.DISMISS);
            if (updated == 0) {
                throw new ProblemNotOpenException();
            }
            OrderProblem resolved = problems.findById(problemId).orElseThrow(ProblemNotFoundException::new);
            events.record(RebyteExchanges.ORDER_PROBLEM_RESOLVED, order.getId(), ProblemEvent.of(resolved, order));
            return ProblemView.from(resolved);
        });
    }

    public AdminProblemPage queue(String status, String cursor, Integer requestedSize) {
        int size = requestedSize == null ? 20 : requestedSize;
        if (size < 1 || size > 50) {
            throw new InvalidProblemFilterException();
        }
        Cursor after = decode(cursor);
        List<ProblemStatus> statuses = switch (status == null ? "" : status) {
            case "" -> List.of(ProblemStatus.OPEN, ProblemStatus.REFUND_PENDING);
            case "OPEN" -> List.of(ProblemStatus.OPEN);
            case "REFUND_PENDING" -> List.of(ProblemStatus.REFUND_PENDING);
            default -> throw new InvalidProblemFilterException();
        };
        List<OrderProblem> rows = problems.queue(statuses, after.createdAt(), after.id(), PageRequest.of(0, size + 1));
        int resultSize = Math.min(rows.size(), size);
        List<AdminProblemPage.Item> items = new ArrayList<>(resultSize);
        for (OrderProblem problem : rows.subList(0, resultSize)) {
            items.add(AdminProblemPage.Item.from(problem));
        }
        String next = rows.size() > size ? encode(rows.get(resultSize - 1)) : null;
        return new AdminProblemPage(List.copyOf(items), next);
    }

    private Cursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidProblemFilterException();
            }
            return new Cursor(Instant.parse(parts[0]), parts[1]);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidProblemFilterException();
        }
    }

    private String encode(OrderProblem problem) {
        String value = problem.getCreatedAt() + "|" + problem.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Claim claimRefund(OrderProblem problem, String actorSub, String note) {
        if (problem.getStatus() == ProblemStatus.REFUND_PENDING) {
            return new Claim(problem.getId(), problem.getOrderId());
        }
        if (problem.getStatus() != ProblemStatus.OPEN
                || problems.claimRefund(problem.getId(), actorSub, note, ProblemStatus.OPEN,
                        ProblemStatus.REFUND_PENDING) == 0) {
            throw new ProblemNotOpenException();
        }
        return new Claim(problem.getId(), problem.getOrderId());
    }

    private void requestRefund(Claim claim) {
        try {
            admin.refund(claim.orderId(), REFUND_REASON);
        } catch (RefundNotEligibleException | ResponseStatusException failure) {
            transactions.executeWithoutResult(status -> problems.revertRefundClaim(claim.problemId(),
                    ProblemStatus.OPEN, ProblemStatus.REFUND_PENDING));
            throw failure;
        }
    }

    private record Claim(String problemId, String orderId) {
    }

    private record Cursor(Instant createdAt, String id) {
    }

}
