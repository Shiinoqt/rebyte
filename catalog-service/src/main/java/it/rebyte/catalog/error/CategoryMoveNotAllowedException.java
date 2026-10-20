package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/** Raised when a listing would be moved below a root inconsistent with its type. */
public class CategoryMoveNotAllowedException extends DomainException {

    public CategoryMoveNotAllowedException(String categoryId, String expectedRoot, String actualRoot) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "category-move-not-allowed",
                "Category " + categoryId + " has root '" + actualRoot + "', not '" + expectedRoot + "'");
    }
}
