package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.DecimalNode;
import tools.jackson.databind.node.DoubleNode;
import tools.jackson.databind.node.IntNode;
import tools.jackson.databind.node.NullNode;
import tools.jackson.databind.node.StringNode;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.dto.AttributeValueInput;
import it.rebyte.catalog.error.AttributeTypeMismatchException;
import it.rebyte.catalog.error.MissingRequiredAttributeException;
import it.rebyte.catalog.error.UnknownAttributeException;
import it.rebyte.catalog.service.AttributeValueValidator;

/**
 * The fast, container-free half of ATTR-03: every rule {@link
 * AttributeValueValidator} enforces, as a pure function over an
 * effective-schema list and submitted {@link AttributeValueInput} values —
 * no Mongo, no Spring context. {@code PublishHardwareIT} proves the same
 * rules end to end through the real endpoint.
 */
class AttributeValidationTest {

    private final AttributeValueValidator validator = new AttributeValueValidator();

    private static AttributeDefinition def(String id, String name, AttributeType type, boolean required, List<String> allowed) {
        return new AttributeDefinition(id, name, type, required, allowed, null, Instant.now());
    }

    @Test
    void emptySchemaWithNoSubmittedValuesProducesAnEmptyResult() {
        Map<String, AttributeValue> result = validator.validate(List.of(), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void missingRequiredAttributeIsRejected() {
        AttributeDefinition required = def("def-1", "vram_gb", AttributeType.INTEGER, true, List.of());

        assertThatThrownBy(() -> validator.validate(List.of(required), List.of()))
                .isInstanceOf(MissingRequiredAttributeException.class);
    }

    @Test
    void missingOptionalAttributeIsAccepted() {
        AttributeDefinition optional = def("def-1", "vram_gb", AttributeType.INTEGER, false, List.of());

        Map<String, AttributeValue> result = validator.validate(List.of(optional), List.of());

        assertThat(result).isEmpty();
    }

    @Test
    void jsonNullValueIsTreatedAsAbsent() {
        AttributeDefinition required = def("def-1", "vram_gb", AttributeType.INTEGER, true, List.of());
        AttributeValueInput nullInput = new AttributeValueInput("def-1", NullNode.getInstance());

        assertThatThrownBy(() -> validator.validate(List.of(required), List.of(nullInput)))
                .isInstanceOf(MissingRequiredAttributeException.class);
    }

    @Test
    void unknownAttributeIdIsRejected() {
        AttributeValueInput unknown = new AttributeValueInput("does-not-exist", IntNode.valueOf(1));

        assertThatThrownBy(() -> validator.validate(List.of(), List.of(unknown)))
                .isInstanceOf(UnknownAttributeException.class);
    }

    @Test
    void textValueForAnIntegerAttributeIsRejected() {
        AttributeDefinition integerDef = def("def-1", "vram_gb", AttributeType.INTEGER, true, List.of());
        AttributeValueInput textInput = new AttributeValueInput("def-1", StringNode.valueOf("16"));

        assertThatThrownBy(() -> validator.validate(List.of(integerDef), List.of(textInput)))
                .isInstanceOf(AttributeTypeMismatchException.class);
    }

    @Test
    void decimalValueForAnIntegerAttributeIsRejected() {
        AttributeDefinition integerDef = def("def-1", "vram_gb", AttributeType.INTEGER, true, List.of());
        AttributeValueInput decimalInput = new AttributeValueInput("def-1", DoubleNode.valueOf(16.5));

        assertThatThrownBy(() -> validator.validate(List.of(integerDef), List.of(decimalInput)))
                .isInstanceOf(AttributeTypeMismatchException.class);
    }

    @Test
    void wholeNumberForAnIntegerAttributeIsAccepted() {
        AttributeDefinition integerDef = def("def-1", "vram_gb", AttributeType.INTEGER, true, List.of());
        AttributeValueInput input = new AttributeValueInput("def-1", IntNode.valueOf(16));

        Map<String, AttributeValue> result = validator.validate(List.of(integerDef), List.of(input));

        assertThat(result.get("def-1").getType()).isEqualTo(AttributeType.INTEGER);
        assertThat(result.get("def-1").getIntegerValue()).isEqualTo(16L);
    }

    @Test
    void decimalValueForADecimalAttributeIsAccepted() {
        AttributeDefinition decimalDef = def("def-1", "price_premium", AttributeType.DECIMAL, true, List.of());
        AttributeValueInput input = new AttributeValueInput("def-1", DecimalNode.valueOf(new BigDecimal("10.50")));

        Map<String, AttributeValue> result = validator.validate(List.of(decimalDef), List.of(input));

        assertThat(result.get("def-1").getType()).isEqualTo(AttributeType.DECIMAL);
        assertThat(result.get("def-1").getDecimalValue()).isEqualByComparingTo("10.50");
    }

    @Test
    void textValueForADecimalAttributeIsRejected() {
        AttributeDefinition decimalDef = def("def-1", "price_premium", AttributeType.DECIMAL, true, List.of());
        AttributeValueInput input = new AttributeValueInput("def-1", StringNode.valueOf("10.50"));

        assertThatThrownBy(() -> validator.validate(List.of(decimalDef), List.of(input)))
                .isInstanceOf(AttributeTypeMismatchException.class);
    }

    @Test
    void booleanValueForABooleanAttributeIsAccepted() {
        AttributeDefinition booleanDef = def("def-1", "rgb", AttributeType.BOOLEAN, true, List.of());
        AttributeValueInput input = new AttributeValueInput("def-1", BooleanNode.TRUE);

        Map<String, AttributeValue> result = validator.validate(List.of(booleanDef), List.of(input));

        assertThat(result.get("def-1").getBooleanValue()).isTrue();
    }

    @Test
    void textCoercibleToBooleanIsStillRejected() {
        AttributeDefinition booleanDef = def("def-1", "rgb", AttributeType.BOOLEAN, true, List.of());
        AttributeValueInput input = new AttributeValueInput("def-1", StringNode.valueOf("true"));

        assertThatThrownBy(() -> validator.validate(List.of(booleanDef), List.of(input)))
                .isInstanceOf(AttributeTypeMismatchException.class);
    }

    @Test
    void enumValueInTheCurrentlyAllowedListIsAccepted() {
        AttributeDefinition enumDef = def("def-1", "bus", AttributeType.ENUM, true, List.of("PCIe", "AGP"));
        AttributeValueInput input = new AttributeValueInput("def-1", StringNode.valueOf("PCIe"));

        Map<String, AttributeValue> result = validator.validate(List.of(enumDef), List.of(input));

        assertThat(result.get("def-1").getStringValue()).isEqualTo("PCIe");
    }

    @Test
    void retiredEnumValueIsRejected() {
        // D-07: the definition's currently allowed list no longer contains "ISA" — this is
        // the validator's-eye view of a retired value; it never sees the historical superset.
        AttributeDefinition enumDef = def("def-1", "bus", AttributeType.ENUM, true, List.of("PCIe", "AGP"));
        AttributeValueInput input = new AttributeValueInput("def-1", StringNode.valueOf("ISA"));

        assertThatThrownBy(() -> validator.validate(List.of(enumDef), List.of(input)))
                .isInstanceOf(AttributeTypeMismatchException.class);
    }

    @Test
    void nonTextualValueForAnEnumAttributeIsRejected() {
        AttributeDefinition enumDef = def("def-1", "bus", AttributeType.ENUM, true, List.of("PCIe"));
        AttributeValueInput input = new AttributeValueInput("def-1", IntNode.valueOf(1));

        assertThatThrownBy(() -> validator.validate(List.of(enumDef), List.of(input)))
                .isInstanceOf(AttributeTypeMismatchException.class);
    }

    @Test
    void everyRequiredAttributeSuppliedProducesTheFullResultMap() {
        AttributeDefinition bus = def("def-1", "bus", AttributeType.ENUM, true, List.of("PCIe"));
        AttributeDefinition vramGb = def("def-2", "vram_gb", AttributeType.INTEGER, true, List.of());
        List<AttributeValueInput> inputs = List.of(
                new AttributeValueInput("def-1", StringNode.valueOf("PCIe")),
                new AttributeValueInput("def-2", IntNode.valueOf(16)));

        Map<String, AttributeValue> result = validator.validate(List.of(bus, vramGb), inputs);

        assertThat(result).hasSize(2);
        assertThat(result.get("def-1").getStringValue()).isEqualTo("PCIe");
        assertThat(result.get("def-2").getIntegerValue()).isEqualTo(16L);
    }

    @Test
    void anyValueReturnsTheOneNonNullFieldMatchingItsType() {
        JsonNode node = IntNode.valueOf(16);
        AttributeValue value = AttributeValue.ofInteger(node.asLong());

        assertThat(value.anyValue()).isEqualTo(16L);
    }
}
