package it.rebyte.catalog.domain;

import java.time.Instant;
import java.util.List;

/**
 * Embedded in the owning {@link Category} document — not a separate
 * collection, because an attribute definition has no life outside its
 * category and the effective-schema walk (D-01) would otherwise be a join
 * per ancestor.
 *
 * <p><b>{@code id} realises D-02.</b> A {@code String} holding a
 * {@code UUID.randomUUID()} value — confirmed at this plan's checkpoint as
 * an explicit, user-decided amendment to {@code docs/rebyte_progettazione.md}
 * §10.6 (which declares surrogate ids as {@code java.util.UUID}), for the
 * same two reasons Phase 1 amended {@code User.id} to {@code String}: it
 * keeps this project's id convention consistent ({@code User.id}/
 * {@code Admin.id} are already {@code String}), and it avoids Spring Data
 * MongoDB's non-{@code String} map-key conversion ambiguity that
 * 02-RESEARCH.md Assumption A2 flags. This id becomes the key every
 * listing's attribute-value map is written under — changing it after
 * listings exist needs a migration over every listing document plus a
 * Meilisearch rebuild in step (one-way).
 *
 * <p><b>{@code name} is stored in its NORMALISED form</b> — Unicode-NFKD
 * decomposed, accent-stripped, lower-cased — the exact same form D-05's
 * uniqueness check compares and the search projection's flat {@code attr.*}
 * namespace is keyed by, so the two can never disagree.
 *
 * <p>{@code allowedValues} is meaningful for {@link AttributeType#ENUM}
 * only. D-07 retirement removes a value from this list without ever
 * touching a listing that already carries it, so the set of values present
 * in indexed data is a superset of this list — search facets must be built
 * from indexed data, never from this definition.
 */
public class AttributeDefinition {

    private final String id;
    private final String name;
    private final AttributeType type;
    private final boolean required;
    private final List<String> allowedValues;
    private final String unit;
    private final Instant definedAt;

    public AttributeDefinition(String id, String name, AttributeType type, boolean required,
            List<String> allowedValues, String unit, Instant definedAt) {
        this.id = id;
        this.name = name;
        this.type = type;
        this.required = required;
        this.allowedValues = allowedValues;
        this.unit = unit;
        this.definedAt = definedAt;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public AttributeType getType() {
        return type;
    }

    public boolean isRequired() {
        return required;
    }

    /** Meaningful for {@link AttributeType#ENUM} only — see class Javadoc on D-07. */
    public List<String> getAllowedValues() {
        return allowedValues;
    }

    /** Optional free label such as "GB", purely for display — it constrains nothing. */
    public String getUnit() {
        return unit;
    }

    public Instant getDefinedAt() {
        return definedAt;
    }
}
