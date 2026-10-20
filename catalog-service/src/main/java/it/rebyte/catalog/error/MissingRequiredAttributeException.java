package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * ATTR-03 / D-06: a definition marked required in the leaf's effective
 * schema has no submitted value. Required is a WRITE-TIME rule — it binds
 * on publish and on edit, never as an always-true invariant over a stored
 * listing (see {@link it.rebyte.catalog.service.AttributeValueValidator}'s
 * class Javadoc).
 */
public class MissingRequiredAttributeException extends DomainException {

    public MissingRequiredAttributeException(String attributeId, String attributeName) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "missing-required-attribute",
                "Missing required attribute '" + attributeName + "' (" + attributeId + ")");
    }
}
