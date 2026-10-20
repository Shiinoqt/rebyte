package it.rebyte.order.dto;

import java.math.BigDecimal;

import it.rebyte.order.domain.PaymentMethod;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code POST /orders} request body. {@code Idempotency-Key} (D-04) travels
 * as a header, not a body field — see {@link it.rebyte.order.controller.OrderController}.
 *
 * <p>{@code address} is optional at the DTO level and validated
 * conditionally in {@link it.rebyte.order.service.OrderCheckoutService}
 * against the freshly-read listing type (V.Order.shippingAddressIffHardware)
 * — its type is not known until the listing is fetched, so a static
 * {@code @NotNull} here would be wrong for a software checkout.
 *
 * <p>{@code offerId} selects checkout from the caller's accepted, unexpired
 * offer (03.1-CONTEXT.md D-07, D-12): the offer's amount replaces {@code
 * listing.price()} as the resolved unit price.
 */
public record CheckoutRequest(
        @NotBlank String listingId,
        @NotNull PaymentMethod method,
        @NotNull BigDecimal expectedTotal,
        @Valid Address address,
        String offerId) {

    /** docs/rebyte_progettazione.md §5.1 Address data type, snapshotted onto the order (D-10). */
    public record Address(
            @NotBlank @Size(max = 150) String recipient,
            @NotBlank @Size(max = 200) String street,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,
            @NotBlank @Size(min = 2, max = 2) String country) {
    }
}
