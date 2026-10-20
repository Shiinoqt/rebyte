package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * D-03's shape rules: an ENUM must supply a non-empty, duplicate-free
 * (after normalisation) allowed-value list; a non-ENUM must supply none at
 * all. A violation is a 422 with a stable code, never a silently-accepted
 * definition.
 */
public class InvalidAttributeDefinitionException extends DomainException {

    public InvalidAttributeDefinitionException(String reason) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-attribute-definition", reason);
    }
}
