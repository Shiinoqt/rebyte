package it.rebyte.payment.controller.internal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.payment.dto.PaymentCreateRequest;
import it.rebyte.payment.dto.PaymentCreateResponse;
import it.rebyte.payment.dto.PaymentStatusResponse;
import it.rebyte.payment.dto.RefundRequest;
import it.rebyte.payment.dto.RefundResponse;
import it.rebyte.payment.service.PaymentRefundService;
import it.rebyte.payment.service.PaymentService;
import it.rebyte.payment.service.PaymentService.CloseOutcome;

import jakarta.validation.Valid;

/**
 * Trusted internal network only; the gateway denies this prefix and
 * OpenAPI excludes this package, mirroring order-service's {@code
 * InternalOrderController}. {@code create} is consumed synchronously by
 * order-service's {@code POST /orders} (D-01); {@code close} is consumed by
 * order-service's future cancel/expiry paths (Task 2's transaction
 * recommendation 5) — not in this plan's declared file list for Task 2, but
 * required by Task 2's own action text ("expose... internal POST
 * /internal/payments/{orderId}/close"), so added here as necessary
 * supporting code (Rule 2/3), same precedent as 03-04's undeclared domain
 * enums/exception classes. {@code refund} is order-service's admin refund
 * command (03-10-PLAN.md Task 2, D-27).
 */
@RestController
@RequestMapping("/internal/payments")
public class InternalPaymentController {

    private final PaymentService paymentService;
    private final PaymentRefundService refundService;

    public InternalPaymentController(PaymentService paymentService, PaymentRefundService refundService) {
        this.paymentService = paymentService;
        this.refundService = refundService;
    }

    @PostMapping
    public PaymentCreateResponse create(@Valid @RequestBody PaymentCreateRequest request) {
        return paymentService.createPayment(request);
    }

    /** D-02 amendment: commits CLOSED only while OPEN; CAPTURING is conflict/unknown, never silently overwritten. */
    @PostMapping("/{orderId}/close")
    public CloseResponse close(@PathVariable String orderId) {
        CloseOutcome outcome = paymentService.close(orderId);
        return new CloseResponse(outcome.closed(), outcome.state());
    }

    /** D-27: order-service has already verified the ADMIN group and PAID/SHIPPED/DELIVERED order state before calling this. */
    @PostMapping("/{orderId}/refund")
    public RefundResponse refund(@PathVariable String orderId, @Valid @RequestBody RefundRequest request) {
        return refundService.refund(orderId, request.reason());
    }

    /**
     * 03-11-PLAN.md orchestrator-directed wiring fix (closes 03-10's D-29
     * gap): order-service's admin stuck-order view reads this instead of
     * payment_db directly. 404 when no Payment row exists for this order.
     */
    @GetMapping("/{orderId}/status")
    public PaymentStatusResponse status(@PathVariable String orderId) {
        return paymentService.status(orderId);
    }

    /** {@code closed=false} means the payment was NOT in {@code OPEN} state — {@code state} reports what it actually was. */
    public record CloseResponse(boolean closed, String state) {
    }
}
