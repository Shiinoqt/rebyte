package it.rebyte.catalog.controller;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import it.rebyte.catalog.domain.AttributeDefinition;
import it.rebyte.catalog.domain.Category;
import it.rebyte.catalog.dto.AttributeDefinitionResponse;
import it.rebyte.catalog.dto.CategoryResponse;
import it.rebyte.catalog.dto.CategoryTreeNode;
import it.rebyte.catalog.dto.CreateCategoryRequest;
import it.rebyte.catalog.dto.DefineAttributeRequest;
import it.rebyte.catalog.dto.EffectiveSchemaResponse;
import it.rebyte.catalog.error.CategoryNotFoundException;
import it.rebyte.catalog.repository.CategoryRepository;
import it.rebyte.catalog.service.AttributeDefinitionService;
import it.rebyte.catalog.service.CategoryService;
import it.rebyte.catalog.service.CategoryTreeCache;
import it.rebyte.catalog.service.CategoryTreeCache.CachedCategory;
import it.rebyte.catalog.service.EffectiveSchemaResolver;

/**
 * {@code POST /categories} — the ADMIN-only method-security expression
 * docs/architecture.md §7c names for category creation. No parallel
 * {@code /admin} URL space: privilege comes from the token, per that same
 * section ("Admins use the ordinary resource endpoints").
 *
 * <p>{@code POST /categories/{id}/attributes} and the allowed-value
 * retirement endpoint (ATTR-02, plan 02-02 Task 2) are ADMIN-gated the
 * same way. {@code GET /categories} (the full browsable tree, CAT-07) and
 * {@code GET /categories/{id}/schema} (the effective schema, D-01) are
 * public with no {@code @PreAuthorize} — a visitor browses the catalogue
 * and the publish form needs the schema before a seller has ever
 * authenticated — and are wired to {@code permitAll()} in {@code
 * SecurityConfig} explicitly, since the default rule for everything else
 * on this controller is {@code authenticated()}. {@code GET
 * /categories/{id}} (the plain single-category lookup) stays behind that
 * default, unchanged from plan 02-02 Task 1.
 */
@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final CategoryService categoryService;
    private final AttributeDefinitionService attributeDefinitionService;
    private final CategoryRepository categoryRepository;
    private final CategoryTreeCache categoryTreeCache;
    private final EffectiveSchemaResolver effectiveSchemaResolver;

    public CategoryController(CategoryService categoryService, AttributeDefinitionService attributeDefinitionService,
            CategoryRepository categoryRepository, CategoryTreeCache categoryTreeCache,
            EffectiveSchemaResolver effectiveSchemaResolver) {
        this.categoryService = categoryService;
        this.attributeDefinitionService = attributeDefinitionService;
        this.categoryRepository = categoryRepository;
        this.categoryTreeCache = categoryTreeCache;
        this.effectiveSchemaResolver = effectiveSchemaResolver;
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request, @AuthenticationPrincipal Jwt jwt) {
        Category category = categoryService.create(jwt.getSubject(), request.name(), request.parentId());
        return CategoryResponse.from(category);
    }

    @GetMapping("/{id}")
    public CategoryResponse getById(@PathVariable String id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new CategoryNotFoundException(id));
        return CategoryResponse.from(category);
    }

    /**
     * CAT-07: both roots down to their leaves in one response, served from
     * {@link CategoryTreeCache} — cheap enough for an unauthenticated
     * visitor to hit repeatedly, since a hit never touches Mongo.
     */
    @GetMapping
    public List<CategoryTreeNode> tree() {
        List<CachedCategory> tree = categoryTreeCache.getTree();
        Map<String, CachedCategory> byId = tree.stream()
                .collect(Collectors.toMap(CachedCategory::id, category -> category));
        return tree.stream()
                .filter(category -> category.parentId() == null)
                .map(root -> toNode(root, byId))
                .toList();
    }

    private static CategoryTreeNode toNode(CachedCategory category, Map<String, CachedCategory> byId) {
        List<CategoryTreeNode> children = category.subcategoryIds().stream()
                .map(byId::get)
                .filter(Objects::nonNull)
                .map(child -> toNode(child, byId))
                .toList();
        return new CategoryTreeNode(category.id(), category.name(), category.subcategoryIds().isEmpty(), children);
    }

    /** D-01's effective schema — the shape a publish form is driven from. */
    @GetMapping("/{id}/schema")
    public EffectiveSchemaResponse schema(@PathVariable String id) {
        return EffectiveSchemaResponse.from(id, effectiveSchemaResolver.effectiveSchema(id));
    }

    @PostMapping("/{id}/attributes")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.CREATED)
    public AttributeDefinitionResponse defineAttribute(@PathVariable String id,
            @Valid @RequestBody DefineAttributeRequest request, @AuthenticationPrincipal Jwt jwt) {
        AttributeDefinition definition = attributeDefinitionService.define(jwt.getSubject(), id, request);
        return AttributeDefinitionResponse.from(definition);
    }

    @DeleteMapping("/{id}/attributes/{attributeId}/allowed-values/{value}")
    @PreAuthorize("hasRole('ADMIN')")
    public AttributeDefinitionResponse retireAllowedValue(
            @PathVariable String id, @PathVariable String attributeId, @PathVariable String value) {
        return AttributeDefinitionResponse.from(attributeDefinitionService.retireAllowedValue(id, attributeId, value));
    }
}
