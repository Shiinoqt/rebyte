package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * ATTR-03: a submitted attribute id is absent from the leaf's effective
 * schema — refused rather than silently stored, so a listing can never
 * carry a value no schema explains.
 */
public class UnknownAttributeException extends DomainException {

    public UnknownAttributeException(String attributeId) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "unknown-attribute",
                "Attribute id '" + attributeId + "' is not part of this category's effective schema");
    }
}
