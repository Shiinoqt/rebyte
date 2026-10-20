package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * No email address can be resolved for the sub: unknown user, or an account
 * without an email attribute. The message names only the sub.
 */
public class UserEmailNotFoundException extends DomainException {

    public UserEmailNotFoundException(String sub) {
        super(HttpStatus.NOT_FOUND, "user-email-not-found", "no email address for user '" + sub + "'");
    }
}
