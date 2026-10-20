package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A rename collides with a handle already held by another user — including
 * a collision that differs only by letter case or accent, since {@code
 * username} uniqueness is declared accent- and case-insensitive (D-58). The
 * unique index is the authority: this exception is always thrown from a
 * caught {@link org.springframework.dao.DataIntegrityViolationException},
 * never from a check-then-write, because a check-then-write races.
 */
public class UsernameConflictException extends DomainException {

    public UsernameConflictException(String conflictingUsername) {
        super(HttpStatus.CONFLICT, "username-conflict",
                "username '" + conflictingUsername + "' is already taken");
    }
}
