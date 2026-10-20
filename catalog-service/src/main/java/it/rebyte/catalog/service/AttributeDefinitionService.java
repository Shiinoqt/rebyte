package it.rebyte.catalog.service;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.text.Normalizer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.AttributeType;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.dto.DefineAttributeRequest;
import it.rebyte.catalog.error.AttributeDefinitionNotFoundException;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.error.DuplicateAttributeNameException;
import it.rebyte.catalog.error.InvalidAttributeDefinitionException;
import it.rebyte.catalog.repository.CategoryRepository;

/**
 * {@code define_attribute} (ATTR-02) and D-07's allowed-value retirement.
 * Deliberately carries NO {@code @PreAuthorize} of its own — same discipline
 * as {@link CategoryService}: the ADMIN gate lives on
 * {@code CategoryController}, where a real authenticated principal always
 * exists.
 *
 * <p><b>Construct locally, push atomically, return the local object</b> —
 * both {@link #define} and {@link #retireAllowedValue} build the resulting
 * {@link AttributeDefinition} in Java first, then apply the equivalent
 * change to Mongo via a single atomic operation on the SAME document
 * ({@code $push} / array-filtered {@code $pull}) purely for the side
 * effect. Neither method depends on Mongo's return value to answer the
 * caller: the object already in hand is the source of truth, matching
 * {@code CategoryService}'s findAndModify style without adding a second
 * round trip.
 *
 * <p>This plan does not add a document-level conflict guard for two admins
 * concurrently defining conflicting names on the SAME category tree — unlike
 * the leaf-guard race (T-02-02-02), no acceptance criterion or threat_model
 * entry in this plan calls for a concurrency proof here, and D-05's
 * uniqueness check already runs against a fresh read on every call. A
 * narrow admin-only race remains theoretically possible under adversarial
 * concurrency; recorded here rather than silently closed with unasked-for
 * machinery (CLAUDE.md: simplicity over abstraction).
 */
@Service
public class AttributeDefinitionService {

    private static final Pattern COMBINING_MARKS = Pattern.compile("\\p{M}+");

    /**
     * D-04's guard, made real rather than merely intended: {@code
     * HardwareListing}'s three fixed fields, normalised the same way {@link
     * #normalize(String)} normalises a submitted attribute name. An
     * attribute defined anywhere under the Hardware root with one of these
     * names would let a hardware listing carry the same concept in two
     * places that can disagree — condition, shipping location and shipping
     * cost are ch. 12 fields, never attribute definitions (02-CONTEXT.md
     * D-04).
     */
    private static final Set<String> HARDWARE_RESERVED_FIELD_NAMES =
            Set.of("condition", "shippinglocation", "shippingcost");

    private final CategoryRepository categoryRepository;
    private final MongoTemplate mongoTemplate;
    private final CategoryTreeCache categoryTreeCache;

    public AttributeDefinitionService(
            CategoryRepository categoryRepository, MongoTemplate mongoTemplate, CategoryTreeCache categoryTreeCache) {
        this.categoryRepository = categoryRepository;
        this.mongoTemplate = mongoTemplate;
        this.categoryTreeCache = categoryTreeCache;
    }

    /**
     * {@code adminSub} is accepted for signature-parity with
     * {@link CategoryService#create} and as a hook for a future audit
     * trail — {@link AttributeDefinition} does not currently store who
     * defined it (not in this plan's field list).
     */
    public AttributeDefinition define(String adminSub, String categoryId, DefineAttributeRequest request) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));

        String normalizedName = normalize(request.name());
        List<String> allowedValues = validateShape(request, normalizedName);
        assertNotReservedFieldName(category, normalizedName);
        assertNameUniqueOnPath(category, normalizedName);

        AttributeDefinition definition = new AttributeDefinition(
                UUID.randomUUID().toString(), normalizedName, request.type(), request.required(),
                allowedValues, request.unit(), Instant.now());

        Query byId = Query.query(where("id").is(categoryId));
        Update pushDefinition = new Update().push("attributeDefinitions", definition);
        mongoTemplate.findAndModify(byId, pushDefinition, Category.class);
        categoryTreeCache.evict();

        return definition;
    }

    /**
     * D-07: retires an allowed value from an ENUM's current list. Never
     * touches a listing that already carries the value — this method has
     * no knowledge of the {@code listing} collection at all, which is
     * exactly what makes that guarantee structural rather than a rule
     * someone has to remember.
     */
    public AttributeDefinition retireAllowedValue(String categoryId, String attributeId, String value) {
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new CategoryNotFoundException(categoryId));
        AttributeDefinition existing = findDefinitionOrThrow(category, categoryId, attributeId);
        if (existing.getType() != AttributeType.ENUM) {
            throw new InvalidAttributeDefinitionException(
                    "attribute '" + existing.getName() + "' is not ENUM; allowed-value retirement only applies to ENUM attributes");
        }

        Query byId = Query.query(where("id").is(categoryId));
        Update pullValue = new Update()
                .filterArray(Criteria.where("elem.id").is(attributeId))
                .pull("attributeDefinitions.$[elem].allowedValues", value);
        mongoTemplate.updateFirst(byId, pullValue, Category.class);
        categoryTreeCache.evict();

        List<String> remaining = existing.getAllowedValues().stream()
                .filter(v -> !v.equals(value))
                .toList();
        return new AttributeDefinition(existing.getId(), existing.getName(), existing.getType(),
                existing.isRequired(), remaining, existing.getUnit(), existing.getDefinedAt());
    }

    private List<String> validateShape(DefineAttributeRequest request, String normalizedName) {
        if (request.type() == AttributeType.ENUM) {
            if (request.allowedValues().isEmpty()) {
                throw new InvalidAttributeDefinitionException(
                        "attribute '" + normalizedName + "' is ENUM and must supply a non-empty allowed-value list");
            }
            return normalizeAndDedupeAllowedValues(request.allowedValues(), normalizedName);
        }
        if (!request.allowedValues().isEmpty()) {
            throw new InvalidAttributeDefinitionException(
                    "attribute '" + normalizedName + "' is " + request.type() + " and must not supply allowed values");
        }
        return List.of();
    }

    /** Stores allowed values in their ORIGINAL casing — only the duplicate check is normalisation-insensitive. */
    private List<String> normalizeAndDedupeAllowedValues(List<String> raw, String attributeName) {
        List<String> result = new ArrayList<>();
        Set<String> seenNormalized = new LinkedHashSet<>();
        for (String value : raw) {
            String trimmed = value.trim();
            if (!seenNormalized.add(normalize(trimmed))) {
                throw new InvalidAttributeDefinitionException(
                        "attribute '" + attributeName + "' has a duplicate allowed value: '" + trimmed + "'");
            }
            result.add(trimmed);
        }
        return List.copyOf(result);
    }

    /**
     * D-04's reserved-field guard: rejects, at definition time, any
     * attribute name colliding with a fixed field on the part the target
     * category's root implies. Plan 02-02's seed data already avoids the
     * collision by construction ({@code bus}/{@code vram_gb}, never {@code
     * condition}), but a future admin is under no such discipline. {@code
     * root().name} is determined from {@link
     * it.rebyte.catalog.domain.Category#getAncestorNames()}'s first entry
     * when non-empty, or the category's own name when it IS the root — safe
     * either way {@code ancestorNames} happens to be conventionally
     * populated, since the root's own name is always the first element of
     * its own chain.
     */
    private void assertNotReservedFieldName(Category category, String normalizedName) {
        List<String> ancestorNames = category.getAncestorNames();
        String rootName = ancestorNames.isEmpty() ? category.getName() : ancestorNames.get(0);
        if ("Hardware".equals(rootName) && HARDWARE_RESERVED_FIELD_NAMES.contains(normalizedName)) {
            throw new DuplicateAttributeNameException(normalizedName, category.getId());
        }
    }

    /**
     * D-05: walks from {@code target} up through every ancestor, then down
     * through every descendant, rejecting a name collision in either
     * direction. This is what makes the effective schema (D-01, Task 3) a
     * plain union with no precedence rule.
     */
    private void assertNameUniqueOnPath(Category target, String normalizedName) {
        Category current = target;
        while (current != null) {
            assertNoCollision(current, normalizedName);
            current = current.getParentId() == null
                    ? null
                    : categoryRepository.findById(current.getParentId()).orElse(null);
        }
        assertNoDescendantCollision(target, normalizedName);
    }

    private void assertNoDescendantCollision(Category category, String normalizedName) {
        for (String childId : category.getSubcategoryIds()) {
            Category child = categoryRepository.findById(childId).orElse(null);
            if (child == null) {
                continue;
            }
            assertNoCollision(child, normalizedName);
            assertNoDescendantCollision(child, normalizedName);
        }
    }

    private void assertNoCollision(Category category, String normalizedName) {
        boolean collides = category.getAttributeDefinitions().stream()
                .anyMatch(definition -> definition.getName().equals(normalizedName));
        if (collides) {
            throw new DuplicateAttributeNameException(normalizedName, category.getId());
        }
    }

    private AttributeDefinition findDefinitionOrThrow(Category category, String categoryId, String attributeId) {
        return category.getAttributeDefinitions().stream()
                .filter(definition -> definition.getId().equals(attributeId))
                .findFirst()
                .orElseThrow(() -> new AttributeDefinitionNotFoundException(categoryId, attributeId));
    }

    /**
     * Unicode-NFKD decomposition + combining-mark strip + lower-case —
     * exactly what makes {@code Vram_GB} collide with {@code vram_gb} and
     * a name differing only by an accent collide with its unaccented form
     * (D-05, edge: encoding).
     */
    static String normalize(String raw) {
        String decomposed = Normalizer.normalize(raw.trim(), Normalizer.Form.NFKD);
        String stripped = COMBINING_MARKS.matcher(decomposed).replaceAll("");
        return stripped.toLowerCase(Locale.ROOT);
    }
}
