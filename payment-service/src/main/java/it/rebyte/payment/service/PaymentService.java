package it.rebyte.payment.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import it.rebyte.payment.client.OrderCaptureAuthorizationClient;
import it.rebyte.payment.client.OrderCaptureAuthorizationClient.CaptureAuthorizationRequest;
import it.rebyte.payment.client.OrderCaptureAuthorizationClient.CaptureAuthorizationResponse;
import it.rebyte.payment.domain.Payment;
import it.rebyte.payment.domain.PaymentMethod;
import it.rebyte.payment.domain.PaymentState;
import it.rebyte.payment.dto.CaptureResponse;
import it.rebyte.payment.dto.PaymentCreateRequest;
import it.rebyte.payment.dto.PaymentCreateResponse;
import it.rebyte.payment.dto.PaymentStatusResponse;
import it.rebyte.payment.error.CaptureDeniedException;
import it.rebyte.payment.error.InvalidPaymentMethodException;
import it.rebyte.payment.error.PaymentClosedException;
import it.rebyte.payment.error.PaymentCreateRetryExpiredException;
import it.rebyte.payment.error.PaymentNotFoundException;
import it.rebyte.payment.error.WrongBuyerException;
import it.rebyte.payment.provider.PayPalClient;
import it.rebyte.payment.provider.PayPalDtos.Amount;
import it.rebyte.payment.provider.PayPalDtos.AmountBreakdown;
import it.rebyte.payment.provider.PayPalDtos.CaptureResult;
import it.rebyte.payment.provider.PayPalDtos.ExperienceContext;
import it.rebyte.payment.provider.PayPalDtos.Item;
import it.rebyte.payment.provider.PayPalDtos.Money;
import it.rebyte.payment.provider.PayPalDtos.OrderRequest;
import it.rebyte.payment.provider.PayPalDtos.OrderResponse;
import it.rebyte.payment.provider.PayPalDtos.PaymentSource;
import it.rebyte.payment.provider.PayPalDtos.PaypalPaymentSource;
import it.rebyte.payment.provider.PayPalDtos.PurchaseUnit;
import it.rebyte.payment.repository.PaymentRepository;

/**
 * {@code POST /internal/payments} (Task 1) and {@code POST
 * /payments/{orderId}/capture} / {@code POST /internal/payments/{orderId}/close}
 * (Task 2, 03-05-PLAN.md; D-01, D-02, D-06, D-36, transaction recommendation
 * 5).
 *
 * <p><b>Create sequence:</b> idempotent-retry short-circuit (a Payment row
 * already exists for this order id) -> otherwise persist the immutable
 * snapshot and a deterministic {@code create-request-id} in its own
 * transaction, COMMITTED before any PayPal call -> outside that
 * transaction, call PayPal Orders v2 create with that id -> persist the
 * provider order id and approval URL with a column-scoped update, so a
 * late provider response cannot overwrite a concurrent close. A retry
 * for the same order id reuses the persisted create request id within a
 * two-second local budget; older unknown outcomes need reconciliation.
 *
 * <p><b>Capture sequence:</b> load Payment, reject a JWT subject that is
 * not the immutable {@code buyerSub} (T-03-10) -> claim the capture
 * operation via the guarded {@code OPEN -> CAPTURING} transition, minting
 * {@code captureOperationId}/{@code captureRequestId} exactly once and
 * reusing them on every repeat request -> a payment already {@code CLOSED}
 * refuses capture -> OUTSIDE any transaction, request the durable grant
 * from order-service; denial, timeout or an unreachable order-service make
 * ZERO PayPal capture calls -> re-check the local state and claim one
 * durable outbound dispatch -> call PayPal at most once. An unknown provider
 * result waits for reconciliation because the sandbox spike did not establish
 * a safe replay window. No capture result — including a successful one — ever marks the
 * order {@code PAID}; that is the verified webhook's job alone (D-26,
 * built in a later plan).
 *
 * <p><b>Reused by the sweep (03-10-PLAN.md Task 1):</b> {@link
 * it.rebyte.payment.service.PaymentReconciliationService} calls {@link
 * #capture(String, String)} directly with the payment's own persisted
 * {@code buyerSub} for a due, provider-APPROVED order — the buyer-subject
 * check is then trivially satisfied, and every claim/grant/dispatch guard
 * above applies identically, so a sweep-triggered capture can never charge
 * twice or race a buyer-triggered one.
 */
@Service
public class PaymentService {

    private static final String CURRENCY = "EUR";
    private static final int TITLE_MAX_LENGTH = 127;
    private static final Duration CREATE_RETRY_BUDGET = Duration.ofSeconds(2);

    private final PaymentRepository repository;
    private final PayPalClient payPalClient;
    private final OrderCaptureAuthorizationClient orderCaptureAuthorizationClient;
    private final TransactionTemplate transactions;
    private final String returnUrl;
    private final String cancelUrl;

    public PaymentService(PaymentRepository repository, PayPalClient payPalClient,
            OrderCaptureAuthorizationClient orderCaptureAuthorizationClient,
            PlatformTransactionManager transactionManager,
            @Value("${rebyte.checkout.return-base-url}") String returnBaseUrl) {
        this.repository = repository;
        this.payPalClient = payPalClient;
        this.orderCaptureAuthorizationClient = orderCaptureAuthorizationClient;
        this.transactions = new TransactionTemplate(transactionManager);
        this.returnUrl = returnBaseUrl + "/checkout/return";
        this.cancelUrl = returnBaseUrl + "/checkout/cancel";
    }

    public PaymentCreateResponse createPayment(PaymentCreateRequest request) {
        if (request.method() == PaymentMethod.BANK_TRANSFER) {
            throw new InvalidPaymentMethodException(request.method());
        }

        Optional<Payment> existing = repository.findById(request.orderId());
        Payment payment = existing.orElseGet(() -> insertPayment(request));

        if (payment.getApprovalUrl() != null) {
            return new PaymentCreateResponse(payment.getProviderOrderId(), payment.getApprovalUrl());
        }
        if (existing.isPresent() && Instant.now().isAfter(payment.getCreatedAt().plus(CREATE_RETRY_BUDGET))) {
            throw new PaymentCreateRetryExpiredException(request.orderId());
        }

        OrderResponse response = payPalClient.createOrder(buildOrderRequest(payment), payment.getCreateRequestId());
        repository.recordProviderOrder(payment.getOrderId(), response.id(), response.approvalUrl());

        return new PaymentCreateResponse(response.id(), response.approvalUrl());
    }

    /** D-01, D-06: the Payment row and its create-request-id commit BEFORE any PayPal call. */
    private Payment insertPayment(PaymentCreateRequest request) {
        Payment payment = new Payment(request.orderId(), request.buyerSub(), request.itemPrice(),
                request.shippingCost(), request.pricePaid(), request.listingTitle(), request.method(),
                UUID.randomUUID().toString(), Instant.now());
        return transactions.execute(status -> repository.saveAndFlush(payment));
    }

    /** D-36: amount.value = pricePaid, breakdown = itemPrice + shippingCost, one item quantity 1. */
    private OrderRequest buildOrderRequest(Payment payment) {
        Money itemTotal = money(payment.getItemPrice());
        Money shipping = money(payment.getShippingCost());
        Money total = money(payment.getPricePaid());

        Amount amount = new Amount(CURRENCY, total.value(), new AmountBreakdown(itemTotal, shipping));
        String title = payment.getListingTitle().length() > TITLE_MAX_LENGTH
                ? payment.getListingTitle().substring(0, TITLE_MAX_LENGTH)
                : payment.getListingTitle();
        Item item = new Item(title, "1", itemTotal);
        PurchaseUnit purchaseUnit = new PurchaseUnit(payment.getOrderId(), amount, List.of(item));

        // D-20: creditCard sets the GUEST_CHECKOUT landing page; both methods
        // otherwise build the identical PayPal order (scripts/paypal/sandbox-spike.py
        // build_order_body, proven live in 03-01's sandbox spike).
        String landingPage = payment.getMethod() == PaymentMethod.CREDIT_CARD ? "GUEST_CHECKOUT" : "LOGIN";
        ExperienceContext experienceContext = new ExperienceContext(returnUrl, cancelUrl, "PAY_NOW", landingPage);
        PaymentSource paymentSource = new PaymentSource(new PaypalPaymentSource(experienceContext));

        return new OrderRequest("CAPTURE", List.of(purchaseUnit), paymentSource);
    }

    private Money money(BigDecimal value) {
        return new Money(CURRENCY, value.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
    }

    public CaptureResponse capture(String orderId, String buyerSub) {
        Payment payment = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        if (!payment.getBuyerSub().equals(buyerSub)) {
            throw new WrongBuyerException();
        }

        Payment claimed = claimCaptureOperation(payment);
        if (isTerminal(claimed.getState())) {
            throw new PaymentClosedException(orderId);
        }

        CaptureAuthorizationResponse grant = requestGrant(orderId, claimed.getCaptureOperationId());
        if (grant.outcome() == CaptureAuthorizationResponse.Outcome.DENIED) {
            throw new CaptureDeniedException(orderId);
        }

        // Re-check the durable local state before dispatch: a concurrent
        // close, or the reconciliation sweep's terminal no-charge fencing
        // (03-10-PLAN.md Task 1), may have landed between the claim above
        // and this grant response (transaction recommendation 5).
        Payment recheck = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        if (isTerminal(recheck.getState())) {
            throw new PaymentClosedException(orderId);
        }

        // The provider retry window was not established by the sandbox spike.
        // Claim one outbound attempt durably; an unknown result is reconciled
        // rather than sent again after the provider may have forgotten the ID.
        if (recheck.getProviderCaptureId() == null && repository.claimCaptureDispatch(
                orderId, PaymentState.CAPTURING, Instant.now()) == 1) {
            it.rebyte.payment.provider.PayPalDtos.CaptureResponse providerResponse = payPalClient
                    .captureOrder(recheck.getProviderOrderId(), recheck.getCaptureRequestId());
            CaptureResult result = providerResponse.firstCapture();
            String captureId = result != null ? result.id() : providerResponse.id();
            String status = result != null ? result.status() : providerResponse.status();
            repository.recordCapture(orderId, PaymentState.CAPTURING, captureId, status);
        }

        return new CaptureResponse(orderId, CaptureResponse.Status.ACCEPTED_AWAITING_CONFIRMATION);
    }

    /**
     * {@code CLOSED} and {@code TERMINAL_NO_CHARGE} (03-10-PLAN.md Task 1)
     * are both refused identically here: neither is ever reopened by a
     * capture request, whether the caller is the buyer or the
     * reconciliation sweep replaying the same stable-ID path.
     */
    private boolean isTerminal(PaymentState state) {
        return state == PaymentState.CLOSED || state == PaymentState.TERMINAL_NO_CHARGE;
    }

    /** The guarded {@code OPEN -> CAPTURING} transition: mints operation/request ids exactly once, reused on every repeat request. */
    private Payment claimCaptureOperation(Payment payment) {
        if (payment.getState() != PaymentState.OPEN) {
            return payment;
        }
        String operationId = UUID.randomUUID().toString();
        String requestId = UUID.randomUUID().toString();
        repository.claimCapturing(payment.getOrderId(), operationId, requestId, PaymentState.OPEN,
                PaymentState.CAPTURING);
        return repository.findById(payment.getOrderId())
                .orElseThrow(() -> new PaymentNotFoundException(payment.getOrderId()));
    }

    /**
     * Denial, timeout or an unreachable order-service are all treated
     * identically as DENIED (Task 2 action: "makes zero PayPal capture
     * calls") — the caller never distinguishes them, and no PayPal call
     * follows any of the three.
     */
    private CaptureAuthorizationResponse requestGrant(String orderId, String operationId) {
        try {
            return orderCaptureAuthorizationClient.grant(orderId, new CaptureAuthorizationRequest(operationId));
        } catch (RuntimeException failure) {
            return new CaptureAuthorizationResponse(CaptureAuthorizationResponse.Outcome.DENIED, null);
        }
    }

    public CloseOutcome close(String orderId) {
        Payment payment = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        if (payment.getState() == PaymentState.CLOSED) {
            return new CloseOutcome(true, PaymentState.CLOSED.name());
        }
        int updated = repository.close(orderId, PaymentState.OPEN, PaymentState.CLOSED);
        if (updated == 1) {
            return new CloseOutcome(true, PaymentState.CLOSED.name());
        }
        Payment current = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        return new CloseOutcome(false, current.getState().name());
    }

    /** {@code closed=false} means the payment was NOT {@code OPEN} at close time — {@code state} reports what it actually was (conflict/unknown). */
    public record CloseOutcome(boolean closed, String state) {
    }

    /**
     * {@code GET /internal/payments/{orderId}/status} (03-11-PLAN.md
     * orchestrator-directed wiring fix): a read-only diagnostic projection,
     * never the entity itself. 404 when no Payment row exists for this
     * order id at all — order-service's stuck view reads that as "payment
     * was never created", the same signal a {@code checkoutPhase = CREATING}
     * order already carries locally.
     */
    public PaymentStatusResponse status(String orderId) {
        Payment payment = repository.findById(orderId).orElseThrow(() -> new PaymentNotFoundException(orderId));
        return new PaymentStatusResponse(payment.getOrderId(), payment.getState().name(),
                payment.getProviderOrderId(), payment.getLastPayPalStatus(), payment.isCapturedAwaitingWebhook());
    }
}
