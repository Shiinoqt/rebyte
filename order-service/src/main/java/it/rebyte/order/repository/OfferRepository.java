package it.rebyte.order.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import it.rebyte.order.domain.Offer;
import it.rebyte.order.domain.OfferStatus;
import it.rebyte.order.domain.RejectionReason;
import jakarta.persistence.LockModeType;

public interface OfferRepository extends JpaRepository<Offer, String> {

    /** D-01: skips the catalog read entirely when a listing has no PENDING offer to protect. */
    boolean existsByListingIdAndStatus(String listingId, OfferStatus status);

    /** D-05: every PENDING offer on a removed listing, claimed or not — a removed listing can never be reserved. */
    @Query("select o from Offer o where o.listingId = :listingId "
            + "and o.status = it.rebyte.order.domain.OfferStatus.PENDING")
    List<Offer> findPendingByListing(@Param("listingId") String listingId);

    /** D-05: unlinked ACCEPTED offers on a removed listing expire without a catalog release. */
    @Query("select o from Offer o where o.listingId = :listingId "
            + "and o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED and o.orderId is null")
    List<Offer> findUnlinkedAcceptedByListing(@Param("listingId") String listingId);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.holdReservationId = :holdReservationId, o.acceptClaimedAt = :now "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.PENDING "
            + "and o.holdReservationId is null")
    int claimAccept(@Param("id") String id, @Param("holdReservationId") String holdReservationId,
            @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED, "
            + "o.expiresAt = :expiresAt, o.pendingGuard = null, o.acceptClaimedAt = null "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.PENDING "
            + "and o.holdReservationId = :holdReservationId")
    int markAccepted(@Param("id") String id, @Param("holdReservationId") String holdReservationId,
            @Param("expiresAt") Instant expiresAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.status = it.rebyte.order.domain.OfferStatus.REJECTED, "
            + "o.rejectionReason = :reason, o.pendingGuard = null, o.holdReservationId = null, "
            + "o.acceptClaimedAt = null where o.id = :id "
            + "and o.status = it.rebyte.order.domain.OfferStatus.PENDING")
    int rejectPending(@Param("id") String id, @Param("reason") RejectionReason reason);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.holdReservationId = null, o.acceptClaimedAt = null "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.PENDING "
            + "and o.holdReservationId = :holdReservationId")
    int clearClaim(@Param("id") String id, @Param("holdReservationId") String holdReservationId);

    @Query("select case when count(o) > 0 then true else false end from Offer o "
            + "where o.listingId = :listingId and o.status = it.rebyte.order.domain.OfferStatus.PENDING "
            + "and o.holdReservationId is not null and (:excludedId is null or o.id <> :excludedId)")
    boolean existsOtherClaim(@Param("listingId") String listingId, @Param("excludedId") String excludedId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.listingId = :listingId "
            + "and o.status = it.rebyte.order.domain.OfferStatus.PENDING order by o.id")
    List<Offer> findPendingForUpdate(@Param("listingId") String listingId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.listingId = :listingId and o.id <> :excludedId "
            + "and o.status = it.rebyte.order.domain.OfferStatus.PENDING order by o.id")
    List<Offer> findPendingSiblingsForUpdate(@Param("listingId") String listingId,
            @Param("excludedId") String excludedId);

    /** Checkout-from-offer's guarded link (03.1-CONTEXT.md D-07, D-10, D-12): 0 rows means the offer expired or already has a live order — the insert must roll back. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.orderId = :orderId where o.id = :id "
            + "and o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED and o.orderId is null "
            + "and o.expiresAt > :now")
    int linkOrder(@Param("id") String id, @Param("orderId") String orderId, @Param("now") Instant now);

    /** A definite reserve loss or unrecoverable retry failure verifying the hold during checkout: no order was ever linked. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.status = it.rebyte.order.domain.OfferStatus.EXPIRED "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED and o.orderId is null")
    int expireUnlinkedHold(@Param("id") String id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Offer o where o.id = :id")
    Optional<Offer> findByIdForUpdate(@Param("id") String id);

    /** D-08: an in-hold recoverable cancellation hands the hold back to the offer, which stays ACCEPTED and re-checkoutable until expiresAt. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.orderId = null where o.id = :id "
            + "and o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED and o.orderId = :orderId")
    int unlinkOrder(@Param("id") String id, @Param("orderId") String orderId);

    /** D-10: a past-deadline cancellation expires the offer, keeping the order link as history so the release event still carries the hold id. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.status = it.rebyte.order.domain.OfferStatus.EXPIRED "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED and o.orderId = :orderId")
    int expireLinked(@Param("id") String id, @Param("orderId") String orderId);

    /** OFR-04: due holds for the sweep hook — ACCEPTED, no live order link, expiry reached (03.1-CONTEXT.md D-10). */
    @Query("select o from Offer o where o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED "
            + "and o.orderId is null and o.expiresAt <= :now order by o.expiresAt asc")
    List<Offer> findDueHolds(@Param("now") Instant now, Pageable pageable);

    /**
     * Guarded expiry of one due hold; the {@code orderId is null} and
     * {@code expiresAt <= :now} predicates are the exact mirror-image of
     * {@link #linkOrder}'s {@code orderId is null and expiresAt > :now}, so
     * exactly one of the two guarded updates can ever change the row.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.status = it.rebyte.order.domain.OfferStatus.EXPIRED "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.ACCEPTED "
            + "and o.orderId is null and o.expiresAt <= :now")
    int expireDueHold(@Param("id") String id, @Param("now") Instant now);

    /** D-06: PENDING offers whose accept claim has outlived rebyte.offers.accept-claim-timeout. */
    @Query("select o from Offer o where o.status = it.rebyte.order.domain.OfferStatus.PENDING "
            + "and o.holdReservationId is not null and o.acceptClaimedAt < :cutoff order by o.acceptClaimedAt asc")
    List<Offer> findStaleClaims(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** OFR-03: the buyer withdraws a still-pending offer, clearing the pending guard so a new offer can be sent. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("update Offer o set o.status = it.rebyte.order.domain.OfferStatus.WITHDRAWN, "
            + "o.pendingGuard = null, o.holdReservationId = null, o.acceptClaimedAt = null "
            + "where o.id = :id and o.status = it.rebyte.order.domain.OfferStatus.PENDING")
    int withdrawPending(@Param("id") String id);

    @Query("select o from Offer o where o.buyerSub = :sub and (:listingId is null or o.listingId = :listingId) "
            + "and (:beforeCreatedAt is null or o.createdAt < :beforeCreatedAt "
            + "or (o.createdAt = :beforeCreatedAt and o.id < :beforeId)) "
            + "order by o.createdAt desc, o.id desc")
    List<Offer> sent(@Param("sub") String sub, @Param("listingId") String listingId,
            @Param("beforeCreatedAt") Instant beforeCreatedAt, @Param("beforeId") String beforeId,
            Pageable pageable);

    @Query("select o from Offer o where o.sellerSub = :sub and (:listingId is null or o.listingId = :listingId) "
            + "and (:beforeCreatedAt is null or o.createdAt < :beforeCreatedAt "
            + "or (o.createdAt = :beforeCreatedAt and o.id < :beforeId)) "
            + "order by o.createdAt desc, o.id desc")
    List<Offer> received(@Param("sub") String sub, @Param("listingId") String listingId,
            @Param("beforeCreatedAt") Instant beforeCreatedAt, @Param("beforeId") String beforeId,
            Pageable pageable);
}
