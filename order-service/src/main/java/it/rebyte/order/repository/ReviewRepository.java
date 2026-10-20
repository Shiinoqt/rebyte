package it.rebyte.order.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.rebyte.order.domain.Review;

public interface ReviewRepository extends JpaRepository<Review, String> {

    boolean existsByOrderId(String orderId);

    Optional<Review> findByOrderId(String orderId);

    @Query("select r from Review r where r.sellerSub = :sub and "
            + "(:listingId is null or r.listingId = :listingId) and "
            + "(:beforeCreatedAt is null or r.createdAt < :beforeCreatedAt "
            + "or (r.createdAt = :beforeCreatedAt and r.id < :beforeId)) "
            + "order by r.createdAt desc, r.id desc")
    List<Review> bySeller(@Param("sub") String sub, @Param("listingId") String listingId,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") String beforeId, Pageable pageable);
}
