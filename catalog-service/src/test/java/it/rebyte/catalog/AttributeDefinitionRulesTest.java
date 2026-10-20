package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.dto.DefineAttributeRequest;
import it.rebyte.catalog.error.DuplicateAttributeNameException;
import it.rebyte.catalog.error.InvalidAttributeDefinitionException;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.AttributeDefinitionService;
import it.rebyte.catalog.service.CategoryTreeCache;

/**
 * The fast, container-free half of ATTR-02's rules: D-03's shape rules,
 * the empty allowed-value case, D-05's case- and accent-insensitive name
 * comparison, and D-07's retirement semantics. The real-Mongo/real-HTTP
 * proof (403, 409 naming the ancestor) belongs to
 * {@link CategoryAttributeDefinitionIT}.
 */
@ExtendWith(MockitoExtension.class)
class AttributeDefinitionRulesTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private MongoTemplate mongoTemplate;

    @Mock
    private CategoryTreeCache categoryTreeCache;

    private AttributeDefinitionService service() {
        return new AttributeDefinitionService(categoryRepository, mongoTemplate, categoryTreeCache);
    }

    @Test
    void enumWithEmptyAllowedValuesIsRejected() {
        Category leaf = category("leaf", "GPU", null, List.of());
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        DefineAttributeRequest request = new DefineAttributeRequest("Color", AttributeType.ENUM, false, List.of(), null);

        assertThatThrownBy(() -> service().define("admin-1", "leaf", request))
                .isInstanceOf(InvalidAttributeDefinitionException.class);
    }

    @Test
    void nonEnumWithAllowedValuesIsRejected() {
        Category leaf = category("leaf", "GPU", null, List.of());
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        DefineAttributeRequest request =
                new DefineAttributeRequest("vram_gb", AttributeType.INTEGER, false, List.of("1"), null);

        assertThatThrownBy(() -> service().define("admin-1", "leaf", request))
                .isInstanceOf(InvalidAttributeDefinitionException.class);
    }

    @Test
    void duplicateAllowedValuesAfterNormalisationAreRejected() {
        Category leaf = category("leaf", "GPU", null, List.of());
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        DefineAttributeRequest request =
                new DefineAttributeRequest("color", AttributeType.ENUM, false, List.of("Red", "red"), null);

        assertThatThrownBy(() -> service().define("admin-1", "leaf", request))
                .isInstanceOf(InvalidAttributeDefinitionException.class);
    }

    @Test
    void nameCollidingWithAnAncestorAfterCaseFoldingIsRejected() {
        AttributeDefinition ancestorDef =
                new AttributeDefinition("def-1", "vram_gb", AttributeType.INTEGER, false, List.of(), null, Instant.now());
        Category ancestor = category("ancestor", "Components", null, List.of(ancestorDef));
        Category leaf = categoryWithParent("leaf", "GPU", "ancestor", List.of());
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));
        when(categoryRepository.findById("ancestor")).thenReturn(Optional.of(ancestor));

        DefineAttributeRequest request = new DefineAttributeRequest("Vram_GB", AttributeType.INTEGER, false, List.of(), null);

        assertThatThrownBy(() -> service().define("admin-1", "leaf", request))
                .isInstanceOf(DuplicateAttributeNameException.class);
    }

    @Test
    void nameDifferingOnlyByAnAccentIsRejected() {
        AttributeDefinition ownDef =
                new AttributeDefinition("def-1", "bus", AttributeType.ENUM, false, List.of("PCIe"), null, Instant.now());
        Category leaf = category("leaf", "GPU", null, List.of(ownDef));
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        DefineAttributeRequest request = new DefineAttributeRequest("bûs", AttributeType.ENUM, false, List.of("PCIe"), null);

        assertThatThrownBy(() -> service().define("admin-1", "leaf", request))
                .isInstanceOf(DuplicateAttributeNameException.class);
    }

    @Test
    void nonCollidingNameIsAcceptedAndStoredNormalised() {
        Category leaf = category("leaf", "GPU", null, List.of());
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        DefineAttributeRequest request = new DefineAttributeRequest("Vram_GB", AttributeType.INTEGER, true, List.of(), "GB");

        AttributeDefinition definition = service().define("admin-1", "leaf", request);

        assertThat(definition.getName()).isEqualTo("vram_gb");
        assertThat(definition.isRequired()).isTrue();
        assertThat(definition.getUnit()).isEqualTo("GB");
        assertThat(definition.getType()).isEqualTo(AttributeType.INTEGER);
    }

    @Test
    void retiringAnAllowedValueRemovesItFromTheReturnedDefinitionOnly() {
        AttributeDefinition existing = new AttributeDefinition(
                "def-1", "color", AttributeType.ENUM, false, List.of("Red", "Blue"), null, Instant.now());
        Category leaf = category("leaf", "GPU", null, List.of(existing));
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        AttributeDefinition retired = service().retireAllowedValue("leaf", "def-1", "Red");

        assertThat(retired.getAllowedValues()).containsExactly("Blue");
        assertThat(retired.getId()).isEqualTo("def-1");
        assertThat(retired.getName()).isEqualTo("color");
    }

    @Test
    void retiringOnANonEnumAttributeIsRejected() {
        AttributeDefinition existing =
                new AttributeDefinition("def-1", "vram_gb", AttributeType.INTEGER, false, List.of(), null, Instant.now());
        Category leaf = category("leaf", "GPU", null, List.of(existing));
        when(categoryRepository.findById("leaf")).thenReturn(Optional.of(leaf));

        assertThatThrownBy(() -> service().retireAllowedValue("leaf", "def-1", "16"))
                .isInstanceOf(InvalidAttributeDefinitionException.class);
    }

    private Category category(String id, String name, String parentId, List<AttributeDefinition> definitions) {
        return new Category(id, name, parentId, List.of("Hardware"), List.of(), definitions, "admin-0", Instant.now(), 0L);
    }

    private Category categoryWithParent(
            String id, String name, String parentId, List<AttributeDefinition> definitions) {
        return new Category(
                id, name, parentId, List.of("Hardware", name), List.of(), definitions, "admin-0", Instant.now(), 0L);
    }
}
