package it.rebyte.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.error.DuplicateSiblingNameException;
import it.rebyte.catalog.error.NonLeafCategoryException;

class DefaultCategorySeederTest {

    private final CategoryService service = mock(CategoryService.class);
    private final MongoTemplate mongo = mock(MongoTemplate.class);
    private final DefaultCategorySeeder seeder = new DefaultCategorySeeder(service, mongo);
    private final Map<Sibling, Category> categories = new LinkedHashMap<>();

    @BeforeEach
    void setUp() {
        insert("Hardware", null);
        insert("Software", null);
        when(mongo.findOne(any(Query.class), eq(Category.class))).thenAnswer(invocation -> {
            var query = ((Query) invocation.getArgument(0)).getQueryObject();
            return categories.get(new Sibling(query.getString("name"), query.getString("parentId")));
        });
        when(service.create(eq("system-bootstrap"), anyString(), anyString())).thenAnswer(invocation ->
                insert(invocation.getArgument(1), invocation.getArgument(2)));
    }

    @Test
    void seedsExactlyTheApprovedTreeAndDoesNoWritesOnRepeat() {
        seeder.seedDefaults();

        assertThat(paths()).containsExactlyInAnyOrderElementsOf(expectedPaths());
        Map<Sibling, Category> before = Map.copyOf(categories);
        clearInvocations(service);

        seeder.seedDefaults();

        assertThat(categories).containsExactlyInAnyOrderEntriesOf(before);
        verify(service, never()).create(anyString(), anyString(), anyString());
    }

    @Test
    void preservesExistingIdsAttributesAndCustomCategories() {
        Category hardware = categories.get(new Sibling("Hardware", null));
        Category components = insert("Components", hardware.getId());
        AttributeDefinition definition = new AttributeDefinition("custom-attribute", "Manufacturer",
                AttributeType.ENUM, false, List.of("Custom"), null, Instant.EPOCH);
        Category existing = new Category(components.getId(), components.getName(), components.getParentId(),
                components.getAncestorNames(), List.of(), List.of(definition), "original-admin", Instant.EPOCH, 0L);
        categories.put(new Sibling("Components", hardware.getId()), existing);
        Category custom = insert("Custom hardware", hardware.getId());

        seeder.seedDefaults();

        assertThat(categories.get(new Sibling("Components", hardware.getId()))).isSameAs(existing);
        assertThat(existing.getAttributeDefinitions()).containsExactly(definition);
        assertThat(categories.get(new Sibling("Custom hardware", hardware.getId()))).isSameAs(custom);
        assertThat(categories.get(new Sibling("CPUs", existing.getId())).getParentId()).isEqualTo(existing.getId());
        verify(service, never()).create("system-bootstrap", "Components", hardware.getId());
    }

    @Test
    void usesTheConcurrentWinnersPersistedIdForDescendants() {
        String hardwareId = categories.get(new Sibling("Hardware", null)).getId();
        doAnswer(invocation -> {
            insert("Components", hardwareId);
            throw new DuplicateSiblingNameException("Components", hardwareId);
        }).when(service).create("system-bootstrap", "Components", hardwareId);

        seeder.seedDefaults();

        Category winner = categories.get(new Sibling("Components", hardwareId));
        verify(service).create("system-bootstrap", "CPUs", winner.getId());
        assertThat(paths()).containsExactlyInAnyOrderElementsOf(expectedPaths());
    }

    @Test
    void skipsAnOccupiedBranchButContinuesOtherHardwareAndSoftwareBranches() {
        String hardwareId = categories.get(new Sibling("Hardware", null)).getId();
        doThrow(new NonLeafCategoryException(hardwareId)).when(service)
                .create("system-bootstrap", "Components", hardwareId);

        seeder.seedDefaults();

        assertThat(paths()).doesNotContain("Hardware/Components", "Hardware/Components/CPUs")
                .contains("Hardware/Computers/Laptops", "Software/Utilities");
        verify(service, never()).create(eq("system-bootstrap"), eq("CPUs"), anyString());
    }

    @Test
    void failsWhenADuplicateHasNoAuthoritativeWinner() {
        String hardwareId = categories.get(new Sibling("Hardware", null)).getId();
        doThrow(new DuplicateSiblingNameException("Components", hardwareId)).when(service)
                .create("system-bootstrap", "Components", hardwareId);

        assertThatThrownBy(seeder::seedDefaults).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void requiresTheFixedRootsRatherThanCreatingReplacementRoots() {
        categories.remove(new Sibling("Hardware", null));

        assertThatThrownBy(seeder::seedDefaults).isInstanceOf(IllegalStateException.class);
        verify(service, never()).create(anyString(), anyString(), anyString());
    }

    @Test
    void doesNotHideUnexpectedPersistenceErrors() {
        String hardwareId = categories.get(new Sibling("Hardware", null)).getId();
        IllegalStateException failure = new IllegalStateException("Database unavailable");
        doThrow(failure).when(service).create("system-bootstrap", "Components", hardwareId);

        assertThatThrownBy(seeder::seedDefaults).isSameAs(failure);
    }

    private Category insert(String name, String parentId) {
        List<String> ancestors = new ArrayList<>();
        if (parentId != null) {
            ancestors.addAll(categories.values().stream().filter(category -> parentId.equals(category.getId()))
                    .findFirst().orElseThrow().getAncestorNames());
        }
        ancestors.add(name);
        Category category = new Category(UUID.randomUUID().toString(), name, parentId, ancestors, List.of(),
                List.of(), "system-bootstrap", Instant.EPOCH, 0L);
        categories.put(new Sibling(name, parentId), category);
        return category;
    }

    private Set<String> paths() {
        return categories.values().stream().map(category -> String.join("/", category.getAncestorNames()))
                .collect(Collectors.toSet());
    }

    private Set<String> expectedPaths() {
        return Set.of("Hardware", "Software", "Hardware/Components", "Hardware/Computers",
                "Hardware/Peripherals", "Hardware/Networking", "Hardware/Retrocomputing", "Hardware/Electronics",
                "Hardware/Components/CPUs", "Hardware/Components/Graphics cards", "Hardware/Components/Motherboards",
                "Hardware/Components/Memory", "Hardware/Components/Storage", "Hardware/Components/Power supplies",
                "Hardware/Components/Cases", "Hardware/Components/Cooling", "Hardware/Computers/Desktops",
                "Hardware/Computers/Laptops", "Hardware/Computers/Servers", "Hardware/Computers/Single-board computers",
                "Hardware/Peripherals/Monitors", "Hardware/Peripherals/Keyboards", "Hardware/Peripherals/Mice",
                "Hardware/Peripherals/Audio", "Hardware/Peripherals/Printers", "Hardware/Networking/Routers",
                "Hardware/Networking/Switches", "Hardware/Networking/Network adapters",
                "Hardware/Retrocomputing/Vintage computers", "Hardware/Retrocomputing/Consoles",
                "Hardware/Retrocomputing/Parts and accessories", "Hardware/Electronics/Microcontrollers",
                "Hardware/Electronics/Development boards", "Hardware/Electronics/Sensors",
                "Hardware/Electronics/Test equipment", "Software/Operating systems", "Software/Drivers",
                "Software/Firmware", "Software/Utilities", "Software/Development tools", "Software/Games");
    }

    private record Sibling(String name, String parentId) { }
}
