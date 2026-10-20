package it.rebyte.order.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;


import it.rebyte.order.client.CatalogInternalClient;
import it.rebyte.order.client.FeignErrors;
import it.rebyte.order.client.PaymentInternalClient;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.ListingType;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import it.rebyte.order.domain.PaymentMethod;
import it.rebyte.commons.license.LicenseKeyPattern;
import it.rebyte.order.dto.CatalogListingResponse;
import it.rebyte.order.dto.CheckoutRequest;
import it.rebyte.order.dto.CheckoutResponse;
import it.rebyte.order.dto.PaymentCreateRequest;
import it.rebyte.order.dto.PaymentCreateResponse;
import it.rebyte.order.dto.ReserveListingRequest;
import it.rebyte.order.dto.ReserveListingResponse;
import it.rebyte.order.error.AddressRequirementException;
import it.rebyte.order.error.CheckoutOutcomeUnknownException;
import it.rebyte.order.error.IdempotencyKeyConflictException;
import it.rebyte.order.error.InvalidAmountScaleException;
import it.rebyte.order.error.InvalidPaymentMethodException;
import it.rebyte.order.error.ListingUnavailableException;
import it.rebyte.order.error.OfferHoldUnverifiedException;
import it.rebyte.order.error.OfferListingMismatchException;
import it.rebyte.order.error.OfferNotCheckoutableException;
import it.rebyte.order.error.OfferNotFoundException;
import it.rebyte.order.error.PriceMismatchException;
import it.rebyte.order.error.SelfPurchaseException;
import it.rebyte.order.error.PaymentUnavailableException;
import it.rebyte.order.repository.OfferRepository;
import it.rebyte.order.repository.OrderRepository;

/**
 * {@code POST /orders} (Task 1, 03-04-PLAN.md; D-01, D-09, D-13, D-16,
 * D-17, D-18, D-19).
 *
 * <p><b>Sequence:</b> idempotent-retry short-circuit -> fresh catalog read
 * -> validate (self-purchase, payment method, address, price) -> commit
 * the unique {@code active_listing_guard} row in its own transaction (D-16)
 * -> OUTSIDE that transaction, for HARDWARE only, call catalog reserve with
 * {@code orderId} as the caller-supplied {@code reservationId} (D-17) ->
 * call payment-service synchronously (D-01) -> guarded
 * {@code CREATING -> READY} transition. No MySQL transaction is ever held
 * across a Feign call.
 *
 * <p><b>D-18:</b> no Redis checkout lock anywhere in this flow — the unique
 * guard alone arbitrates concurrent buyers of the same hardware listing.
 *
 */
@Service
public class OrderCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(OrderCheckoutService.class);

    /** docs/rebyte_progettazione.md §5.1 Address.country: ISO 3166-1 alpha-2, restricted to EU members (D-10). */
    private static final Set<String> EU_COUNTRIES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT",
            "LV", "LT", "LU", "MT", "NL", "PL", "PT", "RO", "SK", "SI", "ES", "SE");
    private static final Map<String, String> POSTAL_PATTERNS = Map.ofEntries(
            Map.entry("AT", "\\d{4}"), Map.entry("BE", "\\d{4}"), Map.entry("BG", "\\d{4}"),
            Map.entry("CY", "\\d{4}"), Map.entry("CZ", "\\d{3} ?\\d{2}"),
            Map.entry("DK", "\\d{4}"), Map.entry("EE", "\\d{5}"), Map.entry("FI", "\\d{5}"),
            Map.entry("FR", "\\d{5}"), Map.entry("DE", "\\d{5}"), Map.entry("GR", "\\d{3} ?\\d{2}"),
            Map.entry("HR", "\\d{5}"), Map.entry("HU", "\\d{4}"),
            Map.entry("IE", "[A-Z0-9]{3} ?[A-Z0-9]{4}"), Map.entry("IT", "\\d{5}"),
            Map.entry("LV", "(?:LV-)?\\d{4}"), Map.entry("LT", "(?:LT-)?\\d{5}"),
            Map.entry("LU", "\\d{4}"), Map.entry("MT", "[A-Z]{3} ?\\d{4}"),
            Map.entry("NL", "\\d{4} ?[A-Z]{2}"), Map.entry("PL", "\\d{2}-\\d{3}"),
            Map.entry("PT", "\\d{4}-\\d{3}"), Map.entry("RO", "\\d{6}"),
            Map.entry("SK", "\\d{3} ?\\d{2}"), Map.entry("SI", "\\d{4}"),
            Map.entry("ES", "\\d{5}"), Map.entry("SE", "\\d{3} ?\\d{2}"));

    private final OrderRepository repository;
    private final OfferRepository offers;
    private final OfferHoldService offerHolds;
    private final it.rebyte.order.outbox.DomainEventRecorder events;
    private final CatalogInternalClient catalogClient;
    private final PaymentInternalClient paymentClient;
    private final OrderCancellationService cancellations;
    private final TransactionTemplate transactions;
    private final Duration paymentTimeout;

    public OrderCheckoutService(OrderRepository repository, OfferRepository offers, OfferHoldService offerHolds,
            it.rebyte.order.outbox.DomainEventRecorder events,
            CatalogInternalClient catalogClient, OrderCancellationService cancellations,
            PaymentInternalClient paymentClient, PlatformTransactionManager transactionManager,
            @Value("${rebyte.checkout.payment-timeout-minutes:30}") long paymentTimeoutMinutes) {
        this.repository = repository;
        this.offers = offers;
        this.offerHolds = offerHolds;
        this.events = events;
        this.catalogClient = catalogClient;
        this.paymentClient = paymentClient;
        this.cancellations = cancellations;
        this.transactions = new TransactionTemplate(transactionManager);
        this.paymentTimeout = Duration.ofMinutes(paymentTimeoutMinutes);
    }

    public CheckoutResponse checkout(String buyerSub, String idempotencyKey, CheckoutRequest request) {
        if (request.expectedTotal().scale() > 2) {
            throw new InvalidAmountScaleException();
        }
        String fingerprint = fingerprint(buyerSub, request);

        Optional<Order> existing = repository.findByBuyerSubAndIdempotencyKey(buyerSub, idempotencyKey);
        if (existing.isPresent()) {
            return respondToExisting(existing.get(), fingerprint);
        }

        if (request.offerId() != null) {
            return checkoutFromOffer(buyerSub, idempotencyKey, fingerprint, request);
        }

        CatalogListingResponse listing = fetchListing(request.listingId());
        if (!listing.available()) {
            throw new ListingUnavailableException(request.listingId());
        }
        validateSelfPurchase(buyerSub, listing.sellerSub());
        validatePaymentMethod(request.method(), listing.paymentMethods());
        validateAddress(listing.type(), request.address());
        BigDecimal pricePaid = validateTotal(listing.price(), listing.shippingCost(), request.expectedTotal());

        Instant now = Instant.now();
        Order order = new Order(UUID.randomUUID().toString(), buyerSub, listing.sellerSub(), listing.id(),
                listing.type(), listing.listingTitle(), listing.price(), listing.shippingCost(), pricePaid,
                request.method(), idempotencyKey, fingerprint, now, now.plus(paymentTimeout));
        if (listing.type() == ListingType.SOFTWARE) {
            order.snapshotLicenseKeyPattern(LicenseKeyPattern.isValid(listing.licenceKeyPattern())
                    ? listing.licenceKeyPattern() : null);
        }
        if (listing.type() == ListingType.HARDWARE) {
            order.setActiveListingGuard(listing.id());
            CheckoutRequest.Address address = request.address();
            order.applyShippingAddress(address.recipient(), address.street(), address.city(),
                    address.postalCode(), address.country().toUpperCase(Locale.ROOT));
        }

        Order inserted;
        try {
            inserted = insertGuarded(order);
        } catch (IdempotencyKeyConflictException raced) {
            Order winner = repository.findByBuyerSubAndIdempotencyKey(buyerSub, idempotencyKey)
                    .orElseThrow(() -> raced);
            return respondToExisting(winner, fingerprint);
        }

        if (listing.type() == ListingType.HARDWARE) {
            reserveOrCancel(inserted, listing.version());
        }

        return finishCheckout(inserted);
    }

    /**
     * {@code offerId} branch (03.1-CONTEXT.md D-07, D-08, D-10, D-12): the
     * unit price and the reused catalog hold both come from the accepted
     * offer; the hold is verified (never re-reserved) outside any
     * transaction before the order is inserted.
     */
    private CheckoutResponse checkoutFromOffer(String buyerSub, String idempotencyKey, String fingerprint,
            CheckoutRequest request) {
        Offer offer = offers.findById(request.offerId())
                .orElseThrow(() -> new OfferNotFoundException(request.offerId()));
        if (!offer.getBuyerSub().equals(buyerSub)) {
            throw new OfferNotFoundException(request.offerId());
        }
        if (!offer.getListingId().equals(request.listingId())) {
            throw new OfferListingMismatchException(request.offerId());
        }
        if (offer.getStatus() != OfferStatus.ACCEPTED || offer.getOrderId() != null
                || !Instant.now().isBefore(offer.getExpiresAt())) {
            throw new OfferNotCheckoutableException(request.offerId());
        }

        CatalogListingResponse listing = fetchListing(request.listingId());
        // No listing.available() check here: catalog reports the held listing as SOLD (D-12).
        validatePaymentMethod(request.method(), listing.paymentMethods());
        validateAddress(listing.type(), request.address());
        BigDecimal pricePaid = validateTotal(offer.getAmount(), listing.shippingCost(), request.expectedTotal());

        verifyHold(offer, listing.version());

        Instant now = Instant.now();
        Order order = new Order(UUID.randomUUID().toString(), buyerSub, listing.sellerSub(), listing.id(),
                listing.type(), listing.listingTitle(), offer.getAmount(), listing.shippingCost(), pricePaid,
                request.method(), idempotencyKey, fingerprint, now, now.plus(paymentTimeout));
        order.setActiveListingGuard(listing.id());
        CheckoutRequest.Address address = request.address();
        order.applyShippingAddress(address.recipient(), address.street(), address.city(),
                address.postalCode(), address.country().toUpperCase(Locale.ROOT));
        order.linkOffer(offer.getId(), offer.getHoldReservationId());

        Order inserted;
        try {
            inserted = insertGuardedFromOffer(order, offer.getId());
        } catch (IdempotencyKeyConflictException raced) {
            Order winner = repository.findByBuyerSubAndIdempotencyKey(buyerSub, idempotencyKey)
                    .orElseThrow(() -> raced);
            return respondToExisting(winner, fingerprint);
        }

        return finishCheckout(inserted);
    }

    /**
     * Verifies the offer's hold is still ours before insert (D-07): a
     * definite loss expires the offer instead of leaving an unpayable
     * order; an unknown outcome writes nothing (D-06 pattern).
     */
    private void verifyHold(Offer offer, long expectedVersion) {
        ReserveListingResponse response;
        try {
            response = reserveWithRetry(offer.getHoldReservationId(), offer.getListingId(), expectedVersion);
        } catch (CheckoutOutcomeUnknownException unknown) {
            throw new OfferHoldUnverifiedException(offer.getId(), unknown);
        }
        switch (response.outcome()) {
            case RESERVED -> { }
            case ALREADY_TAKEN, NOT_FOUND, NOT_RESERVABLE -> {
                offerHolds.expireLostHold(offer);
                throw new ListingUnavailableException(offer.getListingId());
            }
        }
    }

    /** D-16 pattern: the order insert, order.created and the offer link guard commit atomically; 0 rows linked rolls the whole insert back. */
    private Order insertGuardedFromOffer(Order order, String offerId) {
        try {
            return transactions.execute(status -> {
                Order saved = repository.saveAndFlush(order);
                events.record("order.created", saved);
                if (offers.linkOrder(offerId, saved.getId(), Instant.now()) == 0) {
                    throw new OfferNotCheckoutableException(offerId);
                }
                return saved;
            });
        } catch (DataIntegrityViolationException conflict) {
            String message = rootMessage(conflict);
            if (message.contains("uk_order_buyer_idempotency")) {
                throw new IdempotencyKeyConflictException(order.getIdempotencyKey());
            }
            throw new ListingUnavailableException(order.getListingId());
        }
    }

    private CheckoutResponse finishCheckout(Order inserted) {
        PaymentCreateResponse payment = createPayment(inserted);
        String approvalUrl = payment.approvalUrl();

        int updated = repository.transitionPhase(inserted.getId(), approvalUrl, payment.paymentId(), CheckoutPhase.CREATING,
                CheckoutPhase.READY);
        if (updated == 0) {
            log.warn("order {} left checkout_phase CREATING when marking READY (already moved on)",
                    inserted.getId());
            throw new CheckoutOutcomeUnknownException(inserted.getId(),
                    new IllegalStateException("checkout phase changed before payment creation completed"));
        }

        return new CheckoutResponse(inserted.getId(), approvalUrl, OrderStatus.AWAITING_PAYMENT);
    }

    private CheckoutResponse respondToExisting(Order existing, String fingerprint) {
        if (!existing.getRequestFingerprint().equals(fingerprint)) {
            throw new IdempotencyKeyConflictException(existing.getIdempotencyKey());
        }
        if (existing.getCheckoutPhase() == CheckoutPhase.CANCELLED) {
            if (existing.getCancellationReason() == CancellationReason.PAYMENT_UNAVAILABLE) {
                throw new PaymentUnavailableException(existing.getId(), null);
            }
            if (existing.getCancellationReason() == CancellationReason.LISTING_UNAVAILABLE) {
                throw new ListingUnavailableException(existing.getListingId());
            }
            return new CheckoutResponse(existing.getId(), existing.getApprovalUrl(), existing.getStatus());
        }
        if (existing.getCheckoutPhase() == CheckoutPhase.CREATING || existing.getApprovalUrl() == null) {
            throw new CheckoutOutcomeUnknownException(existing.getId(),
                    new IllegalStateException("checkout is awaiting a confirmed payment creation"));
        }
        return new CheckoutResponse(existing.getId(), existing.getApprovalUrl(), existing.getStatus());
    }

    private CatalogListingResponse fetchListing(String listingId) {
        try {
            return catalogClient.availability(listingId);
        } catch (RuntimeException failure) {
            if (FeignErrors.isNotFound(failure)) {
                throw new ListingUnavailableException(listingId);
            }
            throw failure;
        }
    }

    private void validateSelfPurchase(String buyerSub, String sellerSub) {
        if (buyerSub.equals(sellerSub)) {
            throw new SelfPurchaseException();
        }
    }

    private void validatePaymentMethod(PaymentMethod method, Set<PaymentMethod> allowed) {
        if (method == PaymentMethod.BANK_TRANSFER || !allowed.contains(method)) {
            throw new InvalidPaymentMethodException(method);
        }
    }

    private void validateAddress(ListingType type, CheckoutRequest.Address address) {
        if (type == ListingType.HARDWARE) {
            if (address == null) {
                throw new AddressRequirementException("Shipping address is required for hardware listings");
            }
            String country = address.country().toUpperCase(Locale.ROOT);
            if (!EU_COUNTRIES.contains(country)) {
                throw new AddressRequirementException("Unsupported shipping country: " + address.country());
            }
            String postalCode = address.postalCode();
            if (!postalCode.toUpperCase(Locale.ROOT).matches(POSTAL_PATTERNS.get(country))) {
                throw new AddressRequirementException("Invalid postal code: " + postalCode);
            }
        } else if (address != null) {
            throw new AddressRequirementException("Shipping address must be absent for software listings");
        }
    }

    /** D-09 amendment: takes the resolved unit price explicitly — {@code listing.price()} for buy-it-now, the accepted offer's amount for an offer checkout. */
    private BigDecimal validateTotal(BigDecimal unitPrice, BigDecimal shippingCost, BigDecimal expectedTotal) {
        if (unitPrice.scale() > 2 || shippingCost.scale() > 2 || expectedTotal.scale() > 2) {
            throw new InvalidAmountScaleException();
        }
        BigDecimal pricePaid = unitPrice.add(shippingCost);
        if (pricePaid.compareTo(expectedTotal) != 0) {
            throw new PriceMismatchException(pricePaid);
        }
        return pricePaid;
    }

    /** D-16: the guard insert lives in its own transaction, committed before any Feign call. */
    private Order insertGuarded(Order order) {
        try {
            return transactions.execute(status -> {
                Order saved = repository.saveAndFlush(order);
                events.record("order.created", saved);
                return saved;
            });
        } catch (DataIntegrityViolationException conflict) {
            String message = rootMessage(conflict);
            if (message.contains("uk_order_buyer_idempotency")) {
                throw new IdempotencyKeyConflictException(order.getIdempotencyKey());
            }
            throw new ListingUnavailableException(order.getListingId());
        }
    }

    private void reserveOrCancel(Order order, long expectedVersion) {
        ReserveListingResponse response;
        try {
            response = reserveWithRetry(order.getReservationId(), order.getListingId(), expectedVersion);
        } catch (CheckoutOutcomeUnknownException unavailable) {
            cancellations.cancel(order.getId(), CancellationReason.LISTING_UNAVAILABLE);
            throw new ListingUnavailableException(order.getListingId());
        }
        switch (response.outcome()) {
            case RESERVED -> { }
            case ALREADY_TAKEN, NOT_FOUND, NOT_RESERVABLE -> {
                cancellations.cancel(order.getId(), CancellationReason.LISTING_UNAVAILABLE);
                throw new ListingUnavailableException(order.getListingId());
            }
        }
    }

    /**
     * D-17 AMENDED: on an unknown outcome order-service retries once with
     * the SAME caller-supplied {@code reservationId} (safe because catalog
     * reserve is idempotent on that id). A later cancellation event fences
     * delayed reserve attempts using the same reservation id. Generalized
     * (Phase 3.1) to take the reservation id explicitly — the order's own
     * id for buy-it-now, the offer's hold id for an offer checkout.
     */
    private ReserveListingResponse reserveWithRetry(String reservationId, String listingId, long expectedVersion) {
        ReserveListingRequest request = new ReserveListingRequest(reservationId, expectedVersion);
        try {
            return catalogClient.reserve(listingId, request);
        } catch (RuntimeException firstFailure) {
            log.warn("catalog reserve failed for reservation {}, retrying once with the same reservationId",
                    reservationId, firstFailure);
            try {
                return catalogClient.reserve(listingId, request);
            } catch (RuntimeException secondFailure) {
                throw new CheckoutOutcomeUnknownException(reservationId, secondFailure);
            }
        }
    }

    private PaymentCreateResponse createPayment(Order order) {
        PaymentCreateRequest request = new PaymentCreateRequest(order.getId(), order.getBuyerSub(),
                order.getItemPrice(), order.getShippingCost(), order.getPricePaid(), order.getListingTitle(),
                order.getMethod());
        try {
            PaymentCreateResponse response = paymentClient.create(request);
            if (response.paymentId() == null || response.paymentId().isBlank()
                    || response.approvalUrl() == null || response.approvalUrl().isBlank()) {
                throw new IllegalStateException("Payment creation response is incomplete");
            }
            return response;
        } catch (RuntimeException failure) {
            cancellations.cancel(order.getId(), CancellationReason.PAYMENT_UNAVAILABLE);
            throw new PaymentUnavailableException(order.getId(), failure);
        }
    }

    private String fingerprint(String buyerSub, CheckoutRequest request) {
        CheckoutRequest.Address address = request.address();
        String addressPart = address == null ? ""
                : address.recipient() + '|' + address.street() + '|' + address.city() + '|' + address.postalCode()
                        + '|' + address.country().toUpperCase(Locale.ROOT);
        String raw = buyerSub + '|' + request.listingId() + '|' + request.method() + '|'
                + request.expectedTotal().stripTrailingZeros().toPlainString() + '|' + addressPart + '|'
                + (request.offerId() == null ? "" : request.offerId());
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 MessageDigest is not available", e);
        }
    }

    private String rootMessage(DataIntegrityViolationException e) {
        Throwable root = e.getMostSpecificCause();
        return root.getMessage() == null ? "" : root.getMessage();
    }
}
