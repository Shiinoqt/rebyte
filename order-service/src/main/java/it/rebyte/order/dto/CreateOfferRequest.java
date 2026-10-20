package it.rebyte.order.dto;

import java.math.BigDecimal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateOfferRequest(@NotBlank String listingId, @NotNull BigDecimal amount) {
}
