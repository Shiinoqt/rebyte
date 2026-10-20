package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * D-05: an attribute name that already exists anywhere on the path to root
 * — or on any descendant, in the other direction — is rejected at
 * definition time. This is what turns the effective schema (D-01) into a
 * plain union needing no precedence rule, and stops a listing from ever
 * holding two values under one name.
 */
public class DuplicateAttributeNameException extends DomainException {

    public DuplicateAttributeNameException(String name, String conflictingCategoryId) {
        super(HttpStatus.CONFLICT, "duplicate-attribute-name",
                "Attribute name '" + name + "' already exists on category " + conflictingCategoryId);
    }
}
