package it.rebyte.payment.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;

import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.dto.RefundResponse;
import it.rebyte.payment.error.PaymentNotFoundException;
import it.rebyte.payment.error.RefundNotEligibleException;
import it.rebyte.payment.provider.PayPalClient;
import it.rebyte.payment.repository.PaymentRepository;

/**
 * {@code POST /internal/payments/{orderId}/refund} (03-10-PLAN.md Task 2;
 * D-27). Full refund only, never partial: PayPal's Payments v2 capture
 * refund with an empty JSON body always refunds the full remaining captured
 * amount (see {@link PayPalClient#refundCapture}) — no amount field is ever
 * sent, so there is no partial-amount code path to accidentally reach.
 *
 * <p><b>Sequence:</b> load Payment -> if not yet claimed, require a
 * webhook-verified COMPLETED capture ({@link Payment#isVerifiedCapture()})
 * and atomically claim the stable {@code reason}/{@code refundRequestId}
 * pair exactly once ({@link PaymentRepository#claimRefund}) -> claim one
 * durable outbound dispatch ({@link PaymentRepository#claimRefundDispatch})
 * -> call PayPal at most once. A repeated call for the same order — the
 * buyer's admin retrying, or a process-restart retry — reuses the SAME
 * claim and never dispatches a second provider call. The HTTP response here
 * only ever reports "pending"; {@code PAYMENT.CAPTURE.REFUNDED} webhook
 * delivery (Plan 08's {@code PaymentWebhookService}, unchanged by this
 * plan) is the only path that advances {@code providerCaptureStatus} to
 * {@code REFUNDED} and emits {@code payment.refunded} — mirrors D-26's "no
 * capture result ever marks PAID" for the refund side.
 */
@Service
public class PaymentRefundService {

    private final PaymentRepository repository;
    private final PayPalClient payPalClient;

    public PaymentRefundService(PaymentRepository repository, PayPalClient payPalClient) {
        this.repository = repository;
        this.payPalClient = payPalClient;
    }

    public RefundResponse refund(String orderId, String reason) {
        Payment payment = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));

        if (payment.getRefundRequestId() == null) {
            if (!payment.isVerifiedCapture()) {
                throw new RefundNotEligibleException(orderId);
            }
            String requestId = UUID.randomUUID().toString();
            repository.claimRefund(orderId, reason, requestId);
            payment = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
            if (payment.getRefundRequestId() == null) {
                // The database-level eligibility guard did not match at claim time
                // (e.g. a concurrent event resolved this payment differently between
                // the check above and the claim) — never proceed without a claimed
                // stable request id.
                throw new RefundNotEligibleException(orderId);
            }
        }

        if (repository.claimRefundDispatch(orderId, Instant.now()) == 1) {
            // Empty JSON body: PayPal refunds the full remaining captured amount only —
            // no amount field exists in this request to ever carry a partial value.
            payPalClient.refundCapture(payment.getProviderCaptureId(), payment.getRefundRequestId());
        }

        return new RefundResponse(orderId, RefundResponse.Status.REFUND_PENDING);
    }
}
