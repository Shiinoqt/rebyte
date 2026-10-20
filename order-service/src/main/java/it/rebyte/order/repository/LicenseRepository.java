package it.rebyte.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import it.rebyte.order.domain.License;
import it.rebyte.order.domain.LicenseStatus;

public interface LicenseRepository extends JpaRepository<License, String> {

    boolean existsByOrderId(String orderId);
    Optional<License> findByOrderId(String orderId);

    @Modifying(flushAutomatically = true)
    @Transactional
    @Query("update License l set l.status = it.rebyte.order.domain.LicenseStatus.REVOKED, l.revokedAt = :now "
            + "where l.orderId = :orderId and l.status = it.rebyte.order.domain.LicenseStatus.ACTIVE")
    int revokeForOrder(@Param("orderId") String orderId, @Param("now") Instant now);

    @Query("select l from License l where l.buyerSub = :buyerSub and "
            + "(:beforeIssuedAt is null or l.issuedAt < :beforeIssuedAt "
            + "or (l.issuedAt = :beforeIssuedAt and l.orderId < :beforeOrderId)) "
            + "order by l.issuedAt desc, l.orderId desc")
    List<License> mine(@Param("buyerSub") String buyerSub, @Param("beforeIssuedAt") Instant beforeIssuedAt,
            @Param("beforeOrderId") String beforeOrderId, Pageable pageable);

    boolean existsByBuyerSubAndListingIdAndStatus(String buyerSub, String listingId, LicenseStatus status);

    @Query("select distinct l.buyerSub from License l where l.listingId = :listingId "
            + "and l.status = it.rebyte.order.domain.LicenseStatus.ACTIVE "
            + "and (:afterBuyerSub is null or l.buyerSub > :afterBuyerSub) order by l.buyerSub asc")
    List<String> holders(@Param("listingId") String listingId, @Param("afterBuyerSub") String afterBuyerSub,
            Pageable pageable);
}
