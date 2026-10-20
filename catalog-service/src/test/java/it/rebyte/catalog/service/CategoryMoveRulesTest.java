package it.rebyte.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.AttributeValue;
import it.rebyte.catalog.dto.AttributeValueInput;
import it.rebyte.catalog.error.MissingRequiredAttributeException;
import tools.jackson.databind.node.LongNode;

class CategoryMoveRulesTest {

    private final AttributeValueValidator validator = new AttributeValueValidator();

    @Test
    void carriesOnlyDestinationSchemaValuesLetsSubmittedValuesOverrideAndReportsDroppedIds() {
        AttributeDefinition inherited = definition("inherited", true);
        AttributeDefinition destinationOnly = definition("destination-only", true);
        Map<String, AttributeValue> stored = Map.of(
                inherited.getId(), AttributeValue.ofInteger(8),
                "source-only", AttributeValue.ofInteger(1));

        Map<String, AttributeValue> carried = ListingEditService.carriedValues(stored, List.of(inherited, destinationOnly));
        Map<String, AttributeValue> validated = validator.validate(List.of(inherited, destinationOnly),
                ListingEditService.mergeCarriedAndSubmitted(carried, List.of(
                        new AttributeValueInput(inherited.getId(), LongNode.valueOf(16)),
                        new AttributeValueInput(destinationOnly.getId(), LongNode.valueOf(24)))));

        assertThat(validated.get(inherited.getId()).getIntegerValue()).isEqualTo(16);
        assertThat(validated.get(destinationOnly.getId()).getIntegerValue()).isEqualTo(24);
        assertThat(ListingEditService.droppedAttributeIds(stored, carried)).containsExactly("source-only");
    }

    @Test
    void rejectsMoveWhenDestinationRequiredValueIsNeitherCarriedNorSubmitted() {
        AttributeDefinition requiredDestinationValue = definition("destination-required", true);

        assertThatThrownBy(() -> validator.validate(List.of(requiredDestinationValue),
                ListingEditService.mergeCarriedAndSubmitted(Map.of(), List.of())))
                .isInstanceOf(MissingRequiredAttributeException.class);
    }

    private AttributeDefinition definition(String id, boolean required) {
        return new AttributeDefinition(id, id, AttributeType.INTEGER, required, List.of(), null, Instant.now());
    }
}
