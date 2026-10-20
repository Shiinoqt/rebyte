package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * Not in the plan's Task 1 file list — added here (Rule 2: missing critical
 * functionality) because {@code [V.Category.fixedRoots]} needs a distinct
 * 422 for "the name isn't 'Hardware' or 'Software'", separate from the 409
 * a root that already exists returns ({@link DuplicateSiblingNameException}).
 * Without this type the root-name-validation acceptance criterion (Task 1)
 * has no exception to throw and no stable code to assert against.
 */
public class InvalidCategoryNameException extends DomainException {

    public InvalidCategoryNameException(String name) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-category-name",
                "A root category must be named 'Hardware' or 'Software', not '" + name + "'");
    }
}
