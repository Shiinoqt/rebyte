package it.rebyte.catalog.service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.dto.AttributeValueInput;
import it.rebyte.catalog.error.AttributeTypeMismatchException;
import it.rebyte.catalog.error.MissingRequiredAttributeException;
import it.rebyte.catalog.error.UnknownAttributeException;

/**
 * The whole of ATTR-03 as a pure function — no Mongo, no Redis, no HTTP —
 * so it can be unit-tested without a container ({@code
 * AttributeValidationTest}). Takes the effective schema resolved by the
 * caller (which, on the publish/edit write path, MUST be resolved inside
 * the same transaction as the write — see {@code
 * ListingPublishService}'s class Javadoc) and the submitted raw values,
 * and returns the typed {@link AttributeValue} map ready to store on a
 * {@code Listing}.
 *
 * <p><b>Required is a WRITE-TIME rule (D-06), not an always-true
 * invariant.</b> It binds on publish and on edit only. A listing that was
 * complete when published stays valid when an admin later adds a required
 * attribute to its category — it is flagged incomplete and the seller is
 * prompted on their next edit, never pulled out of the catalogue by a
 * migration. This class only ever sees writes; nothing in it inspects a
 * stored listing.
 *
 * <p><b>Never trusts a type the client declares alongside a value</b>
 * (T-02-03-01, the attribute type-confusion threat) — {@link
 * AttributeValueInput} carries no {@code type} field at all; the shape of
 * the submitted {@link JsonNode} is checked against the effective schema's
 * OWN declared {@link it.rebyte.catalog.domain.AttributeType}, the only
 * authority.
 *
 * <p><b>D-07's asymmetry.</b> An ENUM value retired from the definition's
 * currently allowed list is rejected here on a new write, while an existing
 * listing that already carries it stays valid — this class only ever
 * compares against the CURRENTLY allowed list, since it never touches a
 * stored value.
 */
@Component
public class AttributeValueValidator {

    public Map<String, AttributeValue> validate(List<AttributeDefinition> effectiveSchema, List<AttributeValueInput> submitted) {
        Map<String, AttributeDefinition> byId = new LinkedHashMap<>();
        for (AttributeDefinition definition : effectiveSchema) {
            byId.put(definition.getId(), definition);
        }

        Map<String, JsonNode> submittedById = new LinkedHashMap<>();
        for (AttributeValueInput input : submitted) {
            if (!byId.containsKey(input.attributeId())) {
                throw new UnknownAttributeException(input.attributeId());
            }
            submittedById.put(input.attributeId(), input.value());
        }

        Map<String, AttributeValue> result = new LinkedHashMap<>();
        for (AttributeDefinition definition : effectiveSchema) {
            JsonNode raw = submittedById.get(definition.getId());
            if (raw == null || raw.isNull()) {
                if (definition.isRequired()) {
                    throw new MissingRequiredAttributeException(definition.getId(), definition.getName());
                }
                continue;
            }
            result.put(definition.getId(), coerce(definition, raw));
        }
        return result;
    }

    private AttributeValue coerce(AttributeDefinition definition, JsonNode raw) {
        return switch (definition.getType()) {
            case ENUM -> coerceEnum(definition, raw);
            case INTEGER -> coerceInteger(definition, raw);
            case DECIMAL -> coerceDecimal(definition, raw);
            case BOOLEAN -> coerceBoolean(definition, raw);
        };
    }

    private AttributeValue coerceEnum(AttributeDefinition definition, JsonNode raw) {
        if (!raw.isTextual() || !definition.getAllowedValues().contains(raw.asText())) {
            throw new AttributeTypeMismatchException(definition.getId(), definition.getName(), definition.getType(),
                    "value must be one of the currently allowed values");
        }
        return AttributeValue.ofEnum(raw.asText());
    }

    private AttributeValue coerceInteger(AttributeDefinition definition, JsonNode raw) {
        if (!raw.isIntegralNumber()) {
            throw new AttributeTypeMismatchException(definition.getId(), definition.getName(), definition.getType(),
                    "value must be a whole number, not a decimal or a numeric string");
        }
        return AttributeValue.ofInteger(raw.asLong());
    }

    private AttributeValue coerceDecimal(AttributeDefinition definition, JsonNode raw) {
        if (!raw.isNumber()) {
            throw new AttributeTypeMismatchException(definition.getId(), definition.getName(), definition.getType(),
                    "value must be a number, not a string");
        }
        BigDecimal value = raw.decimalValue();
        return AttributeValue.ofDecimal(value);
    }

    private AttributeValue coerceBoolean(AttributeDefinition definition, JsonNode raw) {
        if (!raw.isBoolean()) {
            throw new AttributeTypeMismatchException(definition.getId(), definition.getName(), definition.getType(),
                    "value must be a boolean, not anything merely coercible to one");
        }
        return AttributeValue.ofBoolean(raw.asBoolean());
    }
}
