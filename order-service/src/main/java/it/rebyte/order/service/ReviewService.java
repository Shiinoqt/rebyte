package it.rebyte.order.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.commons.messaging.RebyteExchanges;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.Review;
import it.rebyte.order.dto.CreateReviewRequest;
import it.rebyte.order.dto.ReviewView;
import it.rebyte.order.error.OrderActionForbiddenException;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.error.OrderNotReviewableException;
import it.rebyte.order.error.ReviewAlreadyExistsException;
import it.rebyte.order.messaging.ReviewCreatedEvent;
import it.rebyte.order.outbox.DomainEventRecorder;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.repository.ReviewRepository;

@Service
public class ReviewService {

    private final OrderRepository orders;
    private final ReviewRepository reviews;
    private final DomainEventRecorder events;
    private final TransactionTemplate transactions;

    public ReviewService(OrderRepository orders, ReviewRepository reviews, DomainEventRecorder events,
            PlatformTransactionManager transactionManager) {
        this.orders = orders;
        this.reviews = reviews;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public ReviewView create(String orderId, String callerSub, boolean admin, CreateReviewRequest request) {
        try {
            return transactions.execute(status -> createInTransaction(orderId, callerSub, admin, request));
        } catch (DataIntegrityViolationException duplicate) {
            Throwable cause = duplicate.getMostSpecificCause();
            if (cause != null && cause.getMessage() != null && cause.getMessage().contains("uk_review_order")) {
                throw new ReviewAlreadyExistsException();
            }
            throw duplicate;
        }
    }

    private ReviewView createInTransaction(String orderId, String callerSub, boolean admin,
            CreateReviewRequest request) {
        Order order = orders.findByIdForUpdate(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        authorizeBuyer(order, callerSub, admin);
        if (order.getStatus() != OrderStatus.DELIVERED) {
            throw new OrderNotReviewableException();
        }
        if (reviews.existsByOrderId(orderId)) {
            throw new ReviewAlreadyExistsException();
        }
        // Truncated to the column's precision so the returned and published instants equal the stored one.
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        byte rating = (byte) request.rating().intValueExact();
        Review review = reviews.saveAndFlush(
                new Review(UUID.randomUUID().toString(), order, rating, request.comment(), now));
        events.record(RebyteExchanges.REVIEW_CREATED, review.getId(),
                new ReviewCreatedEvent(review.getId(), review.getSellerSub(), rating, now));
        return ReviewView.from(review, null);
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
