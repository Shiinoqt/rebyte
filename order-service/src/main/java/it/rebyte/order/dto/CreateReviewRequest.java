package it.rebyte.order.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The rating is a BigDecimal so that a fractional value is refused by {@code @Digits}
 * instead of being truncated by the JSON mapper.
 */
public record CreateReviewRequest(
        @NotNull @DecimalMin("0") @DecimalMax("5") @Digits(integer = 1, fraction = 0) BigDecimal rating,
        @Size(max = 2000) @Pattern(regexp = "[^\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]*") String comment) {

    public CreateReviewRequest {
        if (comment != null) {
            // Not String.trim()/strip(): both would silently drop control characters at the edges that must be refused.
            comment = comment.replaceAll("^[ \\t\\r\\n]+|[ \\t\\r\\n]+$", "");
            if (comment.isEmpty()) {
                comment = null;
            }
        }
    }
}
