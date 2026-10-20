package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * Not in plan 02-02's Task 2 file list — added here (Rule 2: missing
 * critical functionality) because the allowed-value retirement endpoint
 * needs a clear 404 for a stale or mistyped {@code attributeId}, not a
 * silent {@link java.util.NoSuchElementException} surfacing as a bare 500.
 * Mirrors {@link CategoryNotFoundException}'s reasoning exactly.
 */
public class AttributeDefinitionNotFoundException extends DomainException {

    public AttributeDefinitionNotFoundException(String categoryId, String attributeId) {
        super(HttpStatus.NOT_FOUND, "attribute-definition-not-found",
                "Attribute definition " + attributeId + " not found on category " + categoryId);
    }
}
