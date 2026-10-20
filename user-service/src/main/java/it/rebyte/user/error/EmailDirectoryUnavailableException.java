package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The email directory failed for a reason other than "not found". The
 * message is deliberately generic; the cause stays attached for diagnosis.
 */
public class EmailDirectoryUnavailableException extends DomainException {

    public EmailDirectoryUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "email-directory-unavailable", "email directory is unavailable");
        initCause(cause);
    }
}
