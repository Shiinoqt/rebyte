package it.rebyte.order.service;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.License;
import it.rebyte.order.dto.OrderPage;
import it.rebyte.order.dto.OrderView;
import it.rebyte.order.dto.ProblemView;
import it.rebyte.order.dto.ReviewView;
import it.rebyte.order.error.InvalidOrderCursorException;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.repository.OrderRepository;
import it.rebyte.order.repository.LicenseRepository;
import it.rebyte.order.repository.OrderProblemRepository;
import it.rebyte.order.repository.ReviewRepository;

@Service
public class OrderQueryService {

    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

    private final OrderRepository repository;
    private final LicenseRepository licenses;
    private final OrderProblemRepository problems;
    private final ReviewRepository reviews;

    public OrderQueryService(OrderRepository repository, LicenseRepository licenses, OrderProblemRepository problems,
            ReviewRepository reviews) {
        this.repository = repository;
        this.licenses = licenses;
        this.problems = problems;
        this.reviews = reviews;
    }

    public OrderView detail(String orderId, String subject, boolean admin) {
        Order order = repository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        if (!admin && !subject.equals(order.getBuyerSub()) && !subject.equals(order.getSellerSub())) {
            throw new OrderNotFoundException(orderId);
        }
        License license = licenses.findByOrderId(orderId).orElse(null);
        OrderView.LicenseSummary summary = license == null ? null
                : new OrderView.LicenseSummary(license.getStatus(), license.getIssuedAt(), license.getRevokedAt(),
                        subject.equals(order.getBuyerSub()) ? license.getKey() : null);
        ProblemView problem = problems.findByOrderId(orderId).map(ProblemView::from).orElse(null);
        ReviewView review = reviews.findByOrderId(orderId).map(found -> ReviewView.from(found, null)).orElse(null);
        return view(order, subject, admin, summary, problem, review);
    }

    public OrderPage purchases(String subject, String cursor, Integer size) {
        PageWindow window = window(cursor, size);
        return page(repository.purchases(subject, window.beforeCreatedAt(), window.beforeId(),
                PageRequest.of(0, window.size() + 1)), subject, window.size());
    }

    public OrderPage sales(String subject, String cursor, Integer size) {
        PageWindow window = window(cursor, size);
        return page(repository.sales(subject, window.beforeCreatedAt(), window.beforeId(),
                PageRequest.of(0, window.size() + 1)), subject, window.size());
    }

    private OrderPage page(List<Order> rows, String subject, int requestedSize) {
        int limit = Math.min(rows.size(), requestedSize);
        // The repository receives requested size + 1; the extra row proves
        // whether a continuation cursor is needed without a count query.
        List<OrderView> items = new ArrayList<>(limit);
        for (int index = 0; index < limit; index++) {
            items.add(view(rows.get(index), subject, false, null, null, null));
        }
        String nextCursor = rows.size() > limit ? encode(rows.get(limit - 1)) : null;
        return new OrderPage(List.copyOf(items), nextCursor);
    }

    private PageWindow window(String cursor, Integer requestedSize) {
        int size = requestedSize == null ? DEFAULT_PAGE_SIZE : requestedSize;
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new InvalidOrderCursorException();
        }
        if (cursor == null || cursor.isBlank()) {
            return new PageWindow(null, null, size);
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            if (parts.length != 2 || parts[1].isBlank()) {
                throw new InvalidOrderCursorException();
            }
            return new PageWindow(Instant.parse(parts[0]), parts[1], size);
        } catch (IllegalArgumentException | DateTimeException invalid) {
            throw new InvalidOrderCursorException();
        }
    }

    private String encode(Order order) {
        String value = order.getCreatedAt() + "|" + order.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private OrderView view(Order order, String subject, boolean admin, OrderView.LicenseSummary license,
            ProblemView problem, ReviewView review) {
        boolean maySeeAddress = admin || subject.equals(order.getBuyerSub())
                || (subject.equals(order.getSellerSub()) && paidOrLater(order.getStatus()));
        OrderView.ShippingAddress address = maySeeAddress && order.getRecipientName() != null
                ? new OrderView.ShippingAddress(order.getRecipientName(), order.getStreet(), order.getCity(),
                        order.getPostalCode(), order.getCountry())
                : null;
        return new OrderView(order.getId(), order.getListingId(), order.getListingTitle(), order.getListingType(),
                order.getBuyerSub(), order.getSellerSub(), order.getItemPrice(), order.getShippingCost(),
                order.getPricePaid(), order.getMethod(), order.getStatus(), order.getCreatedAt(), order.getExpiryAt(),
                address, order.getPaidAt(), order.getShippedAt(), order.getDeliveredAt(),
                order.getCarrier(), order.getTrackingCode(), order.getRefundRequestedAt(), license, problem, review);
    }

    private boolean paidOrLater(OrderStatus status) {
        return status == OrderStatus.PAID || status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
    }

    private record PageWindow(Instant beforeCreatedAt, String beforeId, int size) {
    }
}
