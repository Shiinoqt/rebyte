package it.rebyte.catalog.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import it.rebyte.catalog.domain.PaymentMethod;
import it.rebyte.catalog.domain.Platform;

public record PublishSoftwareRequest(@NotBlank String title, @NotBlank String description,
        @NotNull @DecimalMin(value = "0.01") @Digits(integer = 8, fraction = 2) BigDecimal price,
        @NotBlank String categoryId,
        List<String> photos, @NotEmpty Set<PaymentMethod> paymentMethods,
        @NotEmpty Set<Platform> platforms, @NotBlank String versionNumber, String notes,
        List<AttributeValueInput> attributes, String licenceKeyPattern) {
    public PublishSoftwareRequest {
        if (photos == null) photos = List.of();
        if (attributes == null) attributes = List.of();
    }
}
