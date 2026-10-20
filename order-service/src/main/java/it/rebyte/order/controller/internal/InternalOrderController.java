package it.rebyte.order.controller.internal;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.Order;
import it.rebyte.order.dto.CaptureAuthorizationRequest;
import it.rebyte.order.dto.CaptureAuthorizationResponse;
import it.rebyte.order.dto.CaptureAuthorizationResponse.Outcome;
import it.rebyte.order.error.OrderNotFoundException;
import it.rebyte.order.repository.OrderRepository;

import jakarta.validation.Valid;

/**
 * Trusted internal network only; the gateway denies this prefix and
 * OpenAPI excludes this package. Consumed by payment-service (a later
 * plan) to atomically claim the capture operation before calling PayPal.
 */
@RestController
@RequestMapping("/internal/orders")
public class InternalOrderController {

    private final OrderRepository repository;

    public InternalOrderController(OrderRepository repository) {
        this.repository = repository;
    }

    /**
     * Grants the capture operation claim exactly once per order: {@code
     * READY -> CAPTURE_AUTHORIZED} with the caller's {@code operationId}
     * persisted. A repeat call with the SAME id is idempotent (GRANTED
     * again); a different id while already CAPTURE_AUTHORIZED, or any call
     * while CREATING/CANCELLED, is DENIED.
     */
    @PostMapping("/{orderId}/capture-authorizations")
    public CaptureAuthorizationResponse grantCaptureAuthorization(@PathVariable String orderId,
            @Valid @RequestBody CaptureAuthorizationRequest request) {
        Order order = repository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));

        if (order.getCheckoutPhase() == CheckoutPhase.READY) {
            int updated = repository.transitionToCaptureAuthorized(orderId, request.operationId(),
                    CheckoutPhase.READY, CheckoutPhase.CAPTURE_AUTHORIZED);
            if (updated == 1) {
                return new CaptureAuthorizationResponse(Outcome.GRANTED, CheckoutPhase.CAPTURE_AUTHORIZED.name());
            }
            order = repository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
        }

        if (order.getCheckoutPhase() == CheckoutPhase.CAPTURE_AUTHORIZED
                && request.operationId().equals(order.getCaptureOperationId())) {
            return new CaptureAuthorizationResponse(Outcome.ALREADY_GRANTED, CheckoutPhase.CAPTURE_AUTHORIZED.name());
        }

        return new CaptureAuthorizationResponse(Outcome.DENIED, order.getCheckoutPhase().name());
    }
}
