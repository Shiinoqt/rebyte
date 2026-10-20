package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A requested username matches the generated-handle shape reserved for
 * JIT-provisioned users — {@code user_<8 hex>}, matched case-insensitively
 * (D-59). Only the precise shape is reserved; {@code user_friendly} and
 * {@code user_group} are not rejected by this exception.
 */
public class ReservedHandleException extends DomainException {

    public ReservedHandleException(String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "reserved-handle", message);
    }
}
