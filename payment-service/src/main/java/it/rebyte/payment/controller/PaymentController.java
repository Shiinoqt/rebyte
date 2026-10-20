package it.rebyte.payment.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import it.rebyte.payment.dto.CaptureResponse;
import it.rebyte.payment.service.PaymentService;

/**
 * {@code /payments/**} (Task 2, 03-05-PLAN.md; D-02). The buyer's {@code
 * sub} is resolved solely from the authenticated {@link Jwt} — never from
 * the request body or path — and compared against the immutable {@code
 * buyerSub} persisted at {@code POST /internal/payments} time.
 */
@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @PostMapping("/{orderId}/capture")
    public CaptureResponse capture(@AuthenticationPrincipal Jwt jwt, @PathVariable String orderId) {
        return paymentService.capture(orderId, jwt.getSubject());
    }
}
