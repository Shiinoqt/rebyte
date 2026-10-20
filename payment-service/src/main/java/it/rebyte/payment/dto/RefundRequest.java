package it.rebyte.payment.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * {@code POST /internal/payments/{orderId}/refund} request (03-10-PLAN.md
 * Task 2, D-27). {@code reason} is mandatory and non-blank; order-service is
 * the internal caller and has already verified the ADMIN group and the
 * PAID/SHIPPED/DELIVERED order state before issuing this command — this
 * service trusts that assertion (network-trusted {@code /internal/**}, same
 * as every other internal endpoint) and independently re-verifies only what
 * it itself owns: that the capture it is refunding was actually confirmed
 * by a real webhook ({@link it.rebyte.payment.domain.Payment#isVerifiedCapture()}).
 */
public record RefundRequest(@NotBlank String reason) {
}
