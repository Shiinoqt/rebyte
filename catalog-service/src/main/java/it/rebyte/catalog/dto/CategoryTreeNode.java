package it.rebyte.catalog.dto;

import java.util.List;

/**
 * One node of {@code GET /categories}' whole-tree response. Deliberately
 * omits {@code listingCount} (T-02-02-06): the browsable tree is public,
 * and the counter is an internal conflict token, not something a visitor
 * should see. Also omits attribute definitions — those are {@code GET
 * /categories/{id}/schema}'s job ({@link EffectiveSchemaResponse}), kept
 * separate so a tree-browsing response stays cheap even for a leaf with a
 * long inherited schema.
 */
public record CategoryTreeNode(String id, String name, boolean leaf, List<CategoryTreeNode> children) {
}
