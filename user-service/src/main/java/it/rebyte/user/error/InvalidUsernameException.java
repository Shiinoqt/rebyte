package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A requested username violates the character-set or length rules
 * (D-59, D-60).
 */
public class InvalidUsernameException extends DomainException {

    public InvalidUsernameException(String message) {
        super(HttpStatus.BAD_REQUEST, "invalid-username", message);
    }
}
