package it.rebyte.catalog.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Body of {@code POST /categories}. {@code parentId} is deliberately
 * nullable and unvalidated for blankness — {@code null} means "create a
 * root", the only shape {@code [V.Category.fixedRoots]} recognises.
 */
public record CreateCategoryRequest(

        @NotBlank
        String name,

        String parentId) {
}
