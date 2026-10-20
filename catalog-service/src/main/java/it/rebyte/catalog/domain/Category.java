package it.rebyte.catalog.domain;

import java.time.Instant;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * The full category aggregate (docs/rebyte_progettazione.md §7.4, ch. 8's
 * four category invariants). Plan 02-01's tracer created a minimal shape
 * with just {@code id}/{@code name}/{@code parentId}/{@code ancestorNames};
 * this plan grows it into the real thing an admin can build a tree with.
 *
 * <p>{@code attributeDefinitions} is added by plan 02-02 Task 2, now that
 * {@link AttributeDefinition} exists as a type — embedded here, not a
 * separate collection, because a definition has no life outside its
 * category and the D-01 inheritance walk would otherwise be a join per
 * ancestor. {@link AttributeDefinitionService} appends to it via an atomic
 * {@code $push} on this same document, mirroring the leaf-guard's
 * findAndModify style below rather than a read-modify-write over the Java
 * object.
 *
 * <p>{@code subcategoryIds} is an ordered list rather than a derived count,
 * so {@link #leaf()} (docs/rebyte_progettazione.md §7.4) is a local check
 * with no second query — the same field the leaf-guard conditional update
 * in {@code CategoryService} and {@code ListingPublishService} reads.
 *
 * <p>The compound unique index on {@code (parentId, name)} is what makes
 * {@code [V.Category.uniqueNameAmongSiblings]} and {@code
 * [V.Category.fixedRoots]} atomic under concurrency: two categories can
 * never share both fields, including the two roots, whose {@code parentId}
 * is {@code null} — MongoDB indexes {@code null} like any other value, so
 * {@code (null, "Hardware")} colliding with a second {@code (null,
 * "Hardware")} is caught by the SAME index a sibling-name collision is
 * caught by, needing no separate mechanism for the root case. Boot's
 * automatic index creation from {@code @CompoundIndex} is OFF by default
 * (Spring Data MongoDB 3.0+) and this module's {@code MongoConfig}
 * deliberately does not extend {@code AbstractMongoClientConfiguration} to
 * turn it on — {@code CategoryRootBootstrap} creates this specific index
 * programmatically at startup instead.
 */
@Document(collection = "category")
@CompoundIndex(name = "uniq_parent_name", def = "{'parentId': 1, 'name': 1}", unique = true)
public class Category {

    @Id
    private final String id;

    private final String name;
    private final String parentId;

    /** Ordered chain from root to self, root first — denormalizes into the search document's {@code categoryPath}. */
    private final List<String> ancestorNames;

    /** Direct children only. {@link #leaf()} is {@code subcategoryIds.isEmpty()} — no second query. */
    private final List<String> subcategoryIds;

    /**
     * This category's own attribute definitions (ATTR-02) — never the
     * inherited ones. {@code EffectiveSchemaResolver} (Task 3) walks the
     * parent chain and unions this list with every ancestor's; D-05's
     * uniqueness-to-root check is what makes that union safe with no
     * precedence rule.
     */
    private final List<AttributeDefinition> attributeDefinitions;

    private final String createdByAdminSub;
    private final Instant createdAt;

    /**
     * A conflict token, not a statistic — counts every listing ever
     * classified in this category regardless of status (ACTIVE, SOLD or
     * REMOVED), never decremented when a listing merely changes status,
     * only when a listing moves OUT of the category (plan 02-05's category
     * move). docs/rebyte_progettazione.md §9.9's {@code create_category}
     * precondition speaks of listings *classified* in the parent, not
     * active ones — an active-only counter would silently under-count and
     * let a child slip in under a parent that still holds withdrawn or
     * removed listings.
     *
     * <p>Read and written ONLY as a conditional-update predicate inside a
     * transaction: {@code CategoryService#create} requires it to be zero
     * before appending a child, and {@code ListingPublishService} requires
     * {@link #subcategoryIds} to be empty before incrementing it. Both
     * writes target this SAME document, so MongoDB's document-level
     * contention is what closes the create-vs-publish race rather than a
     * count query against a stale read (02-RESEARCH.md Pitfall 4,
     * T-02-02-02). The whole-tree cache (Task 3) never carries this field —
     * a cached read here would defeat the whole mechanism.
     */
    private final long listingCount;

    @Version
    private Long version;

    public Category(String id, String name, String parentId, List<String> ancestorNames,
            List<String> subcategoryIds, List<AttributeDefinition> attributeDefinitions,
            String createdByAdminSub, Instant createdAt, long listingCount) {
        this.id = id;
        this.name = name;
        this.parentId = parentId;
        this.ancestorNames = ancestorNames;
        this.subcategoryIds = subcategoryIds;
        this.attributeDefinitions = attributeDefinitions;
        this.createdByAdminSub = createdByAdminSub;
        this.createdAt = createdAt;
        this.listingCount = listingCount;
    }

    /** docs/rebyte_progettazione.md §7.4 {@code leaf()} — no side effect, no second query. */
    public boolean leaf() {
        return subcategoryIds.isEmpty();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getParentId() {
        return parentId;
    }

    public List<String> getAncestorNames() {
        return ancestorNames;
    }

    public List<String> getSubcategoryIds() {
        return subcategoryIds;
    }

    /** This category's OWN definitions only — never inherited ones. See field Javadoc. */
    public List<AttributeDefinition> getAttributeDefinitions() {
        return attributeDefinitions;
    }

    public String getCreatedByAdminSub() {
        return createdByAdminSub;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** The conflict token, not a statistic — see the field Javadoc above. */
    public long getListingCount() {
        return listingCount;
    }

    public Long getVersion() {
        return version;
    }
}
