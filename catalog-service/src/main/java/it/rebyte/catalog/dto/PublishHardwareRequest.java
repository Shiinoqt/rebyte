package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import it.rebyte.catalog.domain.Condition;
import it.rebyte.catalog.domain.PaymentMethod;

/**
 * Corpo di {@code POST /listings/hardware}. Nessun campo {@code sellerSub}
 * deliberatamente (T-02-01-01): il venditore si risolve SEMPRE dal principal
 * autenticato, mai dal corpo — un campo qui permetterebbe a un utente di
 * pubblicare come un altro.
 *
 * <p>{@code photos} carries deliberately no {@code @Size(max = 5)}: a Bean
 * Validation failure surfaces as a generic 400 through the shared {@code
 * MethodArgumentNotValidException} handler, but the five-photo cap is a
 * domain rule with its own 422 ({@link
 * it.rebyte.catalog.error.PhotoLimitExceededException}) — enforced in {@code
 * ListingPublishService}, not here.
 *
 * <p>{@code attributes} defaults to an empty list when the client omits the
 * field entirely — ATTR-03's validator still runs against the leaf's
 * effective schema either way, so an empty-but-required schema is still
 * refused.
 */
public record PublishHardwareRequest(

        @NotBlank
        String title,

        @NotBlank
        String description,

        @NotNull
        @DecimalMin(value = "0.01", message = "price must be positive")
        @Digits(integer = 8, fraction = 2, message = "price must have at most 2 decimal places")
        BigDecimal price,

        @NotBlank
        String categoryId,

        List<String> photos,

        @NotEmpty
        Set<PaymentMethod> paymentMethods,

        @NotNull
        Condition condition,

        @NotBlank
        String shippingLocation,

        @NotNull
        @DecimalMin(value = "0.00", message = "shippingCost must not be negative")
        @Digits(integer = 8, fraction = 2, message = "shippingCost must have at most 2 decimal places")
        BigDecimal shippingCost,

        List<AttributeValueInput> attributes) {

    public PublishHardwareRequest {
        if (photos == null) {
            photos = List.of();
        }
        if (attributes == null) {
            attributes = List.of();
        }
    }
}
