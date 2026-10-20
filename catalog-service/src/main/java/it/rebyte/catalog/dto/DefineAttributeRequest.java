package it.rebyte.catalog.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import it.rebyte.catalog.domain.AttributeType;

/**
 * Body of {@code POST /categories/{id}/attributes}. {@code allowedValues}
 * is validated in the service layer, not here: whether it must be empty or
 * non-empty depends on {@code type} (D-03), which {@code @Valid} alone
 * cannot express across two fields. {@code name} is validated for blankness
 * only — normalisation (D-05) and the uniqueness walk happen in
 * {@code AttributeDefinitionService}.
 */
public record DefineAttributeRequest(

        @NotBlank
        String name,

        @NotNull
        AttributeType type,

        boolean required,

        List<String> allowedValues,

        String unit) {

    public DefineAttributeRequest {
        if (allowedValues == null) {
            allowedValues = List.of();
        }
    }
}
