package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A user attempted to follow themselves (SOCL-01, D-42; ch. 8 line 660,
 * {@code [V.Follow.noSelfFollow]}). Checked here so the caller gets a clean
 * problem body; the {@code follow} table's {@code chk_follow_no_self} CHECK
 * constraint (D-41) is the backstop for any write path that bypasses this
 * service-layer check (T-05-04).
 */
public class SelfFollowException extends DomainException {

    public SelfFollowException(String sub) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "self-follow", "user '" + sub + "' cannot follow themselves");
    }
}
