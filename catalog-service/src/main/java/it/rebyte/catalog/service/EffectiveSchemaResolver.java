package it.rebyte.catalog.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.service.CategoryTreeCache.CachedCategory;

/**
 * D-01's inheritance walk: a leaf's effective schema is its own attribute
 * definitions unioned with every ancestor's, walked to the root. The union
 * is unconditional — D-05's uniqueness-to-root check already guarantees no
 * name collides on any one path, so there is no precedence rule to write
 * here. If a collision is ever observed, D-05 has been violated and the
 * fix belongs in {@code AttributeDefinitionService}, not in this resolver.
 *
 * <p>Reads {@link CategoryTreeCache}, never {@link
 * it.rebyte.catalog.repository.CategoryRepository} directly — the tree
 * rarely changes and this walk runs on every publish/edit validation and
 * every schema-driven publish-form render, so the Redis-cached whole-tree
 * blob avoids a per-request chain of Mongo reads
 * (02-RESEARCH.md Architectural Responsibility Map: "Effective-schema
 * resolution ... category tree rarely changes, so a Redis-cached
 * whole-tree blob avoids per-request ancestor walks against Mongo").
 */
@Service
public class EffectiveSchemaResolver {

    private final CategoryTreeCache categoryTreeCache;

    public EffectiveSchemaResolver(CategoryTreeCache categoryTreeCache) {
        this.categoryTreeCache = categoryTreeCache;
    }

    /**
     * Root-most-ancestor first, then each category's own declaration order
     * — a publish form driven from this response must render identically
     * across requests, which an unordered union would not guarantee. A
     * category with no definitions anywhere on its path resolves to an
     * empty list, which is a valid answer, not an error.
     */
    public List<AttributeDefinition> effectiveSchema(String categoryId) {
        Map<String, CachedCategory> byId = indexById(categoryTreeCache.getTree());
        CachedCategory target = byId.get(categoryId);
        if (target == null) {
            throw new CategoryNotFoundException(categoryId);
        }

        List<CachedCategory> selfToRoot = new ArrayList<>();
        CachedCategory current = target;
        while (current != null) {
            selfToRoot.add(current);
            current = current.parentId() == null ? null : byId.get(current.parentId());
        }
        Collections.reverse(selfToRoot);

        List<AttributeDefinition> effective = new ArrayList<>();
        for (CachedCategory category : selfToRoot) {
            effective.addAll(category.attributeDefinitions());
        }
        return List.copyOf(effective);
    }

    private static Map<String, CachedCategory> indexById(List<CachedCategory> tree) {
        Map<String, CachedCategory> byId = new HashMap<>();
        for (CachedCategory category : tree) {
            byId.put(category.id(), category);
        }
        return byId;
    }
}
