package it.rebyte.catalog.domain;

/**
 * v1 attribute types (D-03): exactly these four, no fifth. FREE TEXT is
 * deliberately absent — it is searchable but not usefully filterable, and
 * it would let an admin define an attribute that accepts anything, which is
 * the opposite of what this phase's differentiator (category-specific,
 * filterable attributes) exists to build. ENUM covers ATTR-02's "allowed
 * values"; INTEGER and DECIMAL are what make Meilisearch range filters
 * possible (02-RESEARCH.md Pattern 4). Do not add a fifth constant without
 * reopening D-03.
 */
public enum AttributeType {
    ENUM,
    INTEGER,
    DECIMAL,
    BOOLEAN
}
