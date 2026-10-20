package it.rebyte.catalog.dto;

import java.time.Instant;
import java.util.List;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;

/**
 * Response for {@code POST /categories/{id}/attributes} and the
 * allowed-value retirement endpoint — never the embedded document itself.
 */
public record AttributeDefinitionResponse(
        String id,
        String name,
        AttributeType type,
        boolean required,
        List<String> allowedValues,
        String unit,
        Instant definedAt) {

    public static AttributeDefinitionResponse from(AttributeDefinition definition) {
        return new AttributeDefinitionResponse(
                definition.getId(), definition.getName(), definition.getType(), definition.isRequired(),
                definition.getAllowedValues(), definition.getUnit(), definition.getDefinedAt());
    }
}
