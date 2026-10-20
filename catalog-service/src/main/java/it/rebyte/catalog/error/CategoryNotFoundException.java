package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A publish/edit request names a {@code categoryId} that does not exist.
 * Not in the plan's original class list — added here (Rule 2: missing
 * critical functionality) because a seller naming a stale or mistyped
 * category id must get a clear 404, not an unhandled repository-empty
 * exception surfacing as a bare 500.
 */
public class CategoryNotFoundException extends DomainException {

    public CategoryNotFoundException(String categoryId) {
        super(HttpStatus.NOT_FOUND, "category-not-found", "Category not found: " + categoryId);
    }
}
