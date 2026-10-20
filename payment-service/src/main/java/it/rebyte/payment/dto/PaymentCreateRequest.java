package it.rebyte.payment.dto;

import java.math.BigDecimal;

import it.rebyte.payment.domain.PaymentMethod;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * {@code POST /internal/payments} request (D-01, D-36). Field names and
 * types are byte-for-byte order-service's {@code PaymentCreateRequest}
 * (order-service/src/main/java/it/rebyte/order/dto/PaymentCreateRequest.java)
 * so Feign's JSON body deserializes directly into this type. {@code
 * buyerSub} is trusted verbatim — it already carries order-service's
 * authenticated buyer, never re-derived here and never accepted from any
 * other caller (T-03-10).
 *
 * <p>{@code /internal/**} is reachable from any host on the internal
 * network, not only order-service, so the amount fields carry the same
 * {@code @Digits} scale constraint catalog-service's publish DTOs use —
 * {@link it.rebyte.payment.service.PaymentService#money} rejects a
 * malformed scale with {@code RoundingMode.UNNECESSARY}, and that must
 * surface as a validated 400 here rather than an unmapped exception after
 * the {@code Payment} row is already persisted.
 */
public record PaymentCreateRequest(@NotBlank String orderId, @NotBlank String buyerSub,
        @NotNull @Digits(integer = 8, fraction = 2, message = "itemPrice must have at most 2 decimal places") BigDecimal itemPrice,
        @NotNull @Digits(integer = 8, fraction = 2, message = "shippingCost must have at most 2 decimal places") BigDecimal shippingCost,
        @NotNull @Digits(integer = 8, fraction = 2, message = "pricePaid must have at most 2 decimal places") BigDecimal pricePaid,
        @NotBlank String listingTitle, @NotNull PaymentMethod method) {
}
