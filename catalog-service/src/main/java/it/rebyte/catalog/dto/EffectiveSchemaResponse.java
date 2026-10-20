package it.rebyte.catalog.dto;

import java.util.List;

import it.rebyte.catalog.domain.AttributeDefinition;

/**
 * Response for {@code GET /categories/{id}/schema} — D-01's effective
 * schema for one category, root-most-ancestor-first, the shape a publish
 * form is driven from.
 */
public record EffectiveSchemaResponse(String categoryId, List<AttributeDefinitionResponse> attributes) {

    public static EffectiveSchemaResponse from(String categoryId, List<AttributeDefinition> definitions) {
        return new EffectiveSchemaResponse(
                categoryId, definitions.stream().map(AttributeDefinitionResponse::from).toList());
    }
}
