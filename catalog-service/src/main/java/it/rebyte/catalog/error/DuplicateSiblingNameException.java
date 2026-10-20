package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * {@code [V.Category.uniqueNameAmongSiblings]} and {@code
 * [V.Category.fixedRoots]} are the SAME uniqueness rule applied to two
 * shapes of "sibling": two categories sharing a real parent, or the two
 * roots, whose shared "parent" is {@code null}. Both are caught by the same
 * compound unique index on {@code (parentId, name)} on {@link
 * it.rebyte.catalog.domain.Category}, surfacing here as a translated {@link
 * org.springframework.dao.DuplicateKeyException} — never a
 * check-then-insert race, since the index enforces this atomically.
 */
public class DuplicateSiblingNameException extends DomainException {

    public DuplicateSiblingNameException(String name, String parentId) {
        super(HttpStatus.CONFLICT, "duplicate-sibling-name", parentId == null
                ? "A root category named '" + name + "' already exists"
                : "Category '" + name + "' already exists under parent " + parentId);
    }
}
