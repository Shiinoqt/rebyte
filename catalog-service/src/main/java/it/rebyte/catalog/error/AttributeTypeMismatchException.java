package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

import it.rebyte.catalog.domain.AttributeType;

/**
 * ATTR-03 / D-03 / D-07: a submitted value does not satisfy the effective
 * schema's declared type for this attribute — a non-integral JSON node for
 * INTEGER, a non-numeric node for DECIMAL, a non-boolean node for BOOLEAN,
 * or an ENUM value absent from the definition's CURRENTLY allowed list
 * (which may have retired the value since an earlier listing stored it —
 * D-07's asymmetry). The client-declared shape of the submitted JSON is
 * never trusted as the type; the schema is the only authority
 * (T-02-03-01).
 */
public class AttributeTypeMismatchException extends DomainException {

    public AttributeTypeMismatchException(String attributeId, String attributeName, AttributeType expectedType, String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "attribute-type-mismatch",
                "Attribute '" + attributeName + "' (" + attributeId + ") expects " + expectedType + ": " + reason);
    }
}
