package it.rebyte.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.service.CategoryTreeCache.CachedCategory;
import it.rebyte.catalog.service.EffectiveSchemaResolver;

/**
 * Pins D-01's worked example exactly, against a mocked {@link
 * CategoryTreeCache} — no container needed, the resolver's own union/walk
 * logic is pure. The live end-to-end proof (real Mongo, real Redis, real
 * HTTP, byte-identical caching) belongs to {@link CategoryTreeBrowseIT}.
 *
 * <p>The tree fixture below realises the plan's Task 3 worked example with
 * {@code condition} deliberately absent — D-04 makes {@code condition} a
 * fixed typed field on {@code HardwareListing}, never an attribute
 * definition, so seeding it here would recreate the exact collision D-04
 * exists to resolve. Inheritance is demonstrated one level down, on {@code
 * bus}, which has no chapter 12 counterpart.
 */
@ExtendWith(MockitoExtension.class)
class EffectiveSchemaResolverTest {

    @Mock
    private CategoryTreeCache categoryTreeCache;

    private EffectiveSchemaResolver resolver() {
        return new EffectiveSchemaResolver(categoryTreeCache);
    }

    private static AttributeDefinition attribute(String id, String name) {
        return new AttributeDefinition(id, name, AttributeType.INTEGER, false, List.of(), null, Instant.now());
    }

    /**
     * Hardware (no defs) &gt; Components (bus) &gt; GPU leaf (vram_gb);
     * Hardware &gt; Retrocomputing (no defs) &gt; Amiga leaf (model);
     * Software root, no defs, no children in this fixture.
     */
    private static List<CachedCategory> workedExampleTree() {
        CachedCategory hardware = new CachedCategory(
                "hardware", "Hardware", null, List.of("components", "retro"), List.of());
        CachedCategory components = new CachedCategory(
                "components", "Components", "hardware", List.of("gpu"), List.of(attribute("bus-def", "bus")));
        CachedCategory gpu = new CachedCategory(
                "gpu", "GPU", "components", List.of(), List.of(attribute("vram-def", "vram_gb")));
        CachedCategory retro = new CachedCategory(
                "retro", "Retrocomputing", "hardware", List.of("amiga"), List.of());
        CachedCategory amiga = new CachedCategory(
                "amiga", "Amiga", "retro", List.of(), List.of(attribute("model-def", "model")));
        CachedCategory software = new CachedCategory("software", "Software", null, List.of(), List.of());
        return List.of(hardware, components, gpu, retro, amiga, software);
    }

    @Test
    void gpuEffectiveSchemaIsBusInheritedThenVramGbOwnRootMostFirst() {
        when(categoryTreeCache.getTree()).thenReturn(workedExampleTree());

        List<AttributeDefinition> schema = resolver().effectiveSchema("gpu");

        assertThat(schema).extracting(AttributeDefinition::getName).containsExactly("bus", "vram_gb");
    }

    @Test
    void amigaEffectiveSchemaIsExactlyModelAndNeverContainsCondition() {
        when(categoryTreeCache.getTree()).thenReturn(workedExampleTree());

        List<AttributeDefinition> schema = resolver().effectiveSchema("amiga");

        assertThat(schema).extracting(AttributeDefinition::getName).containsExactly("model");
        assertThat(schema).noneMatch(def -> "condition".equals(def.getName()));
    }

    @Test
    void categoryWithNoDefinitionsAnywhereOnItsPathResolvesToAnEmptySchema() {
        when(categoryTreeCache.getTree()).thenReturn(workedExampleTree());

        List<AttributeDefinition> schema = resolver().effectiveSchema("retro");

        assertThat(schema).isEmpty();
    }

    @Test
    void softwareRootWithNoDefinitionsAndNoParentAlsoResolvesToAnEmptySchema() {
        when(categoryTreeCache.getTree()).thenReturn(workedExampleTree());

        List<AttributeDefinition> schema = resolver().effectiveSchema("software");

        assertThat(schema).isEmpty();
    }

    @Test
    void unknownCategoryIdIsRejected() {
        when(categoryTreeCache.getTree()).thenReturn(workedExampleTree());

        assertThatThrownBy(() -> resolver().effectiveSchema("does-not-exist"))
                .isInstanceOf(CategoryNotFoundException.class);
    }

    @Test
    void orderingIsStableAcrossRepeatedCallsAgainstTheSameSnapshot() {
        when(categoryTreeCache.getTree()).thenReturn(workedExampleTree());
        EffectiveSchemaResolver resolver = resolver();

        List<String> first = resolver.effectiveSchema("gpu").stream().map(AttributeDefinition::getName).toList();
        List<String> second = resolver.effectiveSchema("gpu").stream().map(AttributeDefinition::getName).toList();

        assertThat(first).containsExactlyElementsOf(second);
    }
}
