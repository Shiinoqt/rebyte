package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * {@code [V.Listing.leafAndConsistentCategory]}: a hardware listing must
 * publish into a leaf whose root is named "Hardware". Not in this plan's
 * frontmatter file list — added here (Rule 2: missing critical
 * functionality) because the plan's own {@code must_haves.truths} names this
 * exact refusal and no existing exception covers it: {@code
 * NonLeafCategoryException} covers only the leaf-guard race, not a
 * type/root mismatch.
 */
public class CategoryRootMismatchException extends DomainException {

    public CategoryRootMismatchException(String categoryId, String expectedRoot, String actualRoot) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "category-root-mismatch",
                "Category " + categoryId + " has root '" + actualRoot + "', not '" + expectedRoot + "'");
    }
}
