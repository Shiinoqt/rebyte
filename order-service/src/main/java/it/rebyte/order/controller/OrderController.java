package it.rebyte.order.controller;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.dto.ShipOrderRequest;
import it.rebyte.order.service.OrderFulfilmentService;
import it.rebyte.order.dto.CheckoutRequest;
import it.rebyte.order.dto.CheckoutResponse;
import it.rebyte.order.dto.OrderPage;
import it.rebyte.order.dto.OrderView;
import it.rebyte.order.dto.ProblemView;
import it.rebyte.order.dto.CreateReviewRequest;
import it.rebyte.order.dto.ReportProblemRequest;
import it.rebyte.order.dto.ReviewView;
import it.rebyte.order.dto.ResolveProblemRequest;
import it.rebyte.order.service.OrderCheckoutService;
import it.rebyte.order.service.OrderCancellationService;
import it.rebyte.order.service.OrderQueryService;
import it.rebyte.order.service.OrderProblemService;
import it.rebyte.order.service.ProblemResolutionService;
import it.rebyte.order.service.ReviewService;

import jakarta.validation.Valid;

/**
 * {@code /orders/**} (Task 1, 03-04-PLAN.md; D-01, D-04). The buyer's
 * {@code sub} is resolved solely from the authenticated {@link Jwt} —
 * never from the request body.
 */
@RestController
@RequestMapping("/orders")
public class OrderController {

    private static final String ADMIN_AUTHORITY = "ROLE_ADMIN";

    private final OrderFulfilmentService fulfilmentService;
    private final OrderCheckoutService checkoutService;
    private final OrderQueryService queryService;
    private final OrderCancellationService cancellationService;
    private final OrderProblemService problemService;
    private final ProblemResolutionService problemResolutionService;
    private final ReviewService reviewService;

    public OrderController(OrderCheckoutService checkoutService, OrderQueryService queryService,
            OrderCancellationService cancellationService, OrderFulfilmentService fulfilmentService,
            OrderProblemService problemService, ProblemResolutionService problemResolutionService,
            ReviewService reviewService) {
        this.fulfilmentService = fulfilmentService;
        this.checkoutService = checkoutService;
        this.queryService = queryService;
        this.cancellationService = cancellationService;
        this.problemService = problemService;
        this.problemResolutionService = problemResolutionService;
        this.reviewService = reviewService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CheckoutResponse checkout(@AuthenticationPrincipal Jwt jwt,
            @RequestHeader("Idempotency-Key") String idempotencyKey, @Valid @RequestBody CheckoutRequest request) {
        return checkoutService.checkout(jwt.getSubject(), idempotencyKey, request);
    }

    @PostMapping("/{id}/cancel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@AuthenticationPrincipal Jwt jwt, @PathVariable String id) {
        cancellationService.cancelByBuyer(id, jwt.getSubject());
    }

    @PostMapping("/{id}/problem")
    @ResponseStatus(HttpStatus.CREATED)
    public ProblemView reportProblem(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String id, @Valid @RequestBody ReportProblemRequest request) {
        boolean admin = isAdmin(authentication);
        return problemService.open(id, jwt.getSubject(), admin, request);
    }

    @PostMapping("/{id}/problem/withdraw")
    public ProblemView withdrawProblem(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String id) {
        boolean admin = isAdmin(authentication);
        return problemService.withdraw(id, jwt.getSubject(), admin);
    }

    @PostMapping("/{id}/problem/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProblemView refundProblem(@AuthenticationPrincipal Jwt jwt, @PathVariable String id,
            @Valid @RequestBody(required = false) ResolveProblemRequest request) {
        return problemResolutionService.sellerRefund(id, jwt.getSubject(), request == null ? null : request.note());
    }

    @PostMapping("/{id}/review")
    @ResponseStatus(HttpStatus.CREATED)
    public ReviewView review(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String id, @Valid @RequestBody CreateReviewRequest request) {
        boolean admin = isAdmin(authentication);
        return reviewService.create(id, jwt.getSubject(), admin, request);
    }

    @PostMapping("/{id}/ship")
    public OrderView ship(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String id, @Valid @RequestBody ShipOrderRequest request) {
        boolean admin = isAdmin(authentication);
        fulfilmentService.ship(id, jwt.getSubject(), admin, request.carrier(), request.trackingCode());
        return queryService.detail(id, jwt.getSubject(), admin);
    }

    @PostMapping("/{id}/deliver")
    public OrderView deliver(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String id) {
        boolean admin = isAdmin(authentication);
        fulfilmentService.deliver(id, jwt.getSubject(), admin);
        return queryService.detail(id, jwt.getSubject(), admin);
    }

    @GetMapping("/{id}")
    public OrderView detail(@AuthenticationPrincipal Jwt jwt, Authentication authentication,
            @PathVariable String id) {
        return queryService.detail(id, jwt.getSubject(), isAdmin(authentication));
    }

    @GetMapping("/me/purchases")
    public OrderPage purchases(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return queryService.purchases(jwt.getSubject(), cursor, size);
    }

    @GetMapping("/me/sales")
    public OrderPage sales(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
        return queryService.sales(jwt.getSubject(), cursor, size);
    }

    private static boolean isAdmin(Authentication authentication) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> ADMIN_AUTHORITY.equals(authority.getAuthority()));
    }
}
