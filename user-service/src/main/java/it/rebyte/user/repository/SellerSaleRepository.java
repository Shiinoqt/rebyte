package it.rebyte.user.repository;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import it.rebyte.user.domain.SellerSale;

public interface SellerSaleRepository extends JpaRepository<SellerSale, String> {

    @Modifying
    @Query(value = "INSERT INTO seller_sale (order_id, seller_sub) VALUES (:orderId, :sellerSub) "
            + "ON DUPLICATE KEY UPDATE order_id = order_id",
            nativeQuery = true)
    int insertIfAbsent(@Param("orderId") String orderId, @Param("sellerSub") String sellerSub);

    @Modifying
    @Query(value = "UPDATE seller_sale SET delivered_at = COALESCE(delivered_at, :at) WHERE order_id = :orderId",
            nativeQuery = true)
    int markDelivered(@Param("orderId") String orderId, @Param("at") Instant at);

    @Modifying
    @Query(value = "UPDATE seller_sale SET refunded_at = COALESCE(refunded_at, :at) WHERE order_id = :orderId",
            nativeQuery = true)
    int markRefunded(@Param("orderId") String orderId, @Param("at") Instant at);

    @Query("SELECT COUNT(s) FROM SellerSale s WHERE s.sellerSub = :sellerSub "
            + "AND s.deliveredAt IS NOT NULL AND s.refundedAt IS NULL")
    long countCompletedSales(@Param("sellerSub") String sellerSub);
}
