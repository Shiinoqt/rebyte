package it.rebyte.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import it.rebyte.order.domain.Carrier;
import it.rebyte.order.domain.CancellationReason;
import it.rebyte.order.domain.CheckoutPhase;
import it.rebyte.order.domain.Order;
import it.rebyte.order.domain.OrderStatus;
import jakarta.persistence.LockModeType;

/**
 * Access to order_db.orders (D-01, D-04, D-16, D-17). Every {@code
 * @Modifying} method here is called OUTSIDE any surrounding transaction
 * from {@link it.rebyte.order.service.OrderCheckoutService} on purpose — no
 * MySQL transaction is ever held across a Feign call (D-16) — so each one
 * carries its own {@code @Transactional} to satisfy Spring Data's
 * requirement that a modifying query run inside an active transaction.
 */
public interface OrderRepository extends JpaRepository<Order, String> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") String id);

    Optional<Order> findByBuyerSubAndIdempotencyKey(String buyerSub, String idempotencyKey);

    @Query("select o from Order o where o.buyerSub = :sub and "
            + "(:beforeCreatedAt is null or o.createdAt < :beforeCreatedAt "
            + "or (o.createdAt = :beforeCreatedAt and o.id < :beforeId)) "
            + "order by o.createdAt desc, o.id desc")
    List<Order> purchases(@Param("sub") String sub, @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") String beforeId, Pageable pageable);

    @Query("select o from Order o where o.sellerSub = :sub and "
            + "(:beforeCreatedAt is null or o.createdAt < :beforeCreatedAt "
            + "or (o.createdAt = :beforeCreatedAt and o.id < :beforeId)) "
            + "order by o.createdAt desc, o.id desc")
    List<Order> sales(@Param("sub") String sub, @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") String beforeId, Pageable pageable);

    @Query("select o from Order o where o.checkoutPhase = it.rebyte.order.domain.CheckoutPhase.CREATING "
            + "and o.expiryAt < :now order by o.expiryAt asc")
    List<Order> expiredCreating(@Param("now") Instant now, Pageable pageable);

    @Query("select o from Order o where "
            + "(o.checkoutPhase = it.rebyte.order.domain.CheckoutPhase.CREATING "
            + "or o.checkoutPhase = it.rebyte.order.domain.CheckoutPhase.CAPTURE_AUTHORIZED "
            + "or (o.status = it.rebyte.order.domain.OrderStatus.AWAITING_PAYMENT and o.expiryAt < :now) "
            + "or o.cancellationReason = it.rebyte.order.domain.CancellationReason.PAYMENT_UNAVAILABLE) "
            + "and (:beforeCreatedAt is null or o.createdAt < :beforeCreatedAt "
            + "or (o.createdAt = :beforeCreatedAt and o.id < :beforeId)) "
            + "order by o.createdAt desc, o.id desc")
    List<Order> adminStuckCandidates(@Param("now") Instant now,
            @Param("beforeCreatedAt") Instant beforeCreatedAt, @Param("beforeId") String beforeId, Pageable pageable);

    /**
     * The guarded {@code CREATING -> READY} transition (D-16): a later
     * failed/slow payment-create call can never revive an order some other
     * path already moved to {@link CheckoutPhase#CANCELLED} — the {@code
     * WHERE checkoutPhase = :fromPhase} clause makes that structurally
     * impossible rather than a race the caller has to reason about.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Order o set o.checkoutPhase = :toPhase, o.approvalUrl = :approvalUrl, "
            + "o.providerOrderId = :providerOrderId "
            + "where o.id = :id and o.checkoutPhase = :fromPhase")
    int transitionPhase(@Param("id") String id, @Param("approvalUrl") String approvalUrl,
            @Param("providerOrderId") String providerOrderId,
            @Param("fromPhase") CheckoutPhase fromPhase, @Param("toPhase") CheckoutPhase toPhase);

    /**
     * The guarded {@code READY -> CAPTURE_AUTHORIZED} transition consumed
     * by {@code POST /internal/orders/{id}/capture-authorizations}: grants
     * the operation id exactly once, denying a claim from any other phase.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Order o set o.checkoutPhase = :toPhase, o.captureOperationId = :operationId "
            + "where o.id = :id and o.checkoutPhase = :fromPhase")
    int transitionToCaptureAuthorized(@Param("id") String id, @Param("operationId") String operationId,
            @Param("fromPhase") CheckoutPhase fromPhase, @Param("toPhase") CheckoutPhase toPhase);

    /** The phase predicate lets a granted capture win atomically over cancellation. */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("update Order o set o.status = :status, o.checkoutPhase = :toPhase, o.activeListingGuard = null, "
            + "o.cancellationReason = :reason where o.id = :id and o.checkoutPhase in :fromPhases "
            + "and o.status = it.rebyte.order.domain.OrderStatus.AWAITING_PAYMENT")
    int cancelFromPhases(@Param("id") String id, @Param("status") OrderStatus status,
            @Param("fromPhases") List<CheckoutPhase> fromPhases, @Param("toPhase") CheckoutPhase toPhase,
            @Param("reason") CancellationReason reason);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Order o set o.status = it.rebyte.order.domain.OrderStatus.SHIPPED, "
            + "o.shippedAt = :now, o.carrier = :carrier, o.trackingCode = :trackingCode "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OrderStatus.PAID "
            + "and o.listingType = it.rebyte.order.domain.ListingType.HARDWARE and o.refundRequestedAt is null")
    int markShipped(@Param("id") String id, @Param("now") Instant now, @Param("carrier") Carrier carrier,
            @Param("trackingCode") String trackingCode);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Order o set o.status = it.rebyte.order.domain.OrderStatus.DELIVERED, o.deliveredAt = :now "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OrderStatus.SHIPPED "
            + "and o.listingType = it.rebyte.order.domain.ListingType.HARDWARE and o.refundRequestedAt is null")
    int markDelivered(@Param("id") String id, @Param("now") Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Order o set o.refundRequestedAt = coalesce(o.refundRequestedAt, :now) "
            + "where o.id = :id and o.status in :statuses")
    int markRefundRequested(@Param("id") String id, @Param("now") Instant now,
            @Param("statuses") List<OrderStatus> statuses);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Order o set o.refundRequestedAt = null where o.id = :id and o.status in "
            + "(it.rebyte.order.domain.OrderStatus.PAID, it.rebyte.order.domain.OrderStatus.SHIPPED, "
            + "it.rebyte.order.domain.OrderStatus.DELIVERED)")
    int clearRefundRequested(@Param("id") String id);

    @Query("select o from Order o where o.status = it.rebyte.order.domain.OrderStatus.PAID "
            + "and o.listingType = it.rebyte.order.domain.ListingType.HARDWARE "
            + "and o.paidAt is not null and o.paidAt < :cutoff "
            + "and ((:retry = false and o.refundRequestedAt is null) "
            + "or (:retry = true and o.refundRequestedAt is not null)) "
            + "and (:afterPaidAt is null or o.paidAt > :afterPaidAt "
            + "or (o.paidAt = :afterPaidAt and o.id > :afterId)) "
            + "order by o.paidAt asc, o.id asc")
    List<Order> shipByCandidates(@Param("cutoff") Instant cutoff, @Param("retry") boolean retry,
            @Param("afterPaidAt") Instant afterPaidAt, @Param("afterId") String afterId, Pageable pageable);

    @Query("select o from Order o where o.status = it.rebyte.order.domain.OrderStatus.SHIPPED "
            + "and o.listingType = it.rebyte.order.domain.ListingType.HARDWARE "
            + "and o.shippedAt < :cutoff and o.refundRequestedAt is null "
            + "and not exists (select p.id from OrderProblem p where p.orderId = o.id and "
            + "(p.status = it.rebyte.order.domain.ProblemStatus.OPEN or "
            + "p.status = it.rebyte.order.domain.ProblemStatus.REFUND_PENDING)) "
            + "order by o.shippedAt asc, o.id asc")
    List<Order> autoDeliverCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);
}
