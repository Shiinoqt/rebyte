package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The document-level race that keeps the implicit "no listings under a
 * non-leaf category" invariant true was lost (docs/rebyte_progettazione.md
 * §9.9's third {@code create_category} precondition; T-02-02-02). Either a
 * child category was requested under a parent whose {@code listingCount} is
 * no longer zero, or a listing was published into a category whose {@code
 * subcategoryIds} is no longer empty. Both write paths perform a
 * conditional update on the SAME category document, so this fires when that
 * conditional update matches nothing — the losing side of a real race, not
 * an afterthought check against a stale read.
 */
public class NonLeafCategoryException extends DomainException {

    public NonLeafCategoryException(String categoryId) {
        super(HttpStatus.CONFLICT, "category-not-leaf",
                "Category " + categoryId + " cannot accept this write: it no longer satisfies the leaf precondition");
    }
}
