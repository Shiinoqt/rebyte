package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * A confirm request named a key outside the caller's own {@code
 * avatars/{sub}/} prefix (D-35, T-06-02). There is no URL that lets a
 * caller point their profile at another user's uploaded object — the
 * ownership check runs before the object store is ever asked about the
 * key.
 */
public class AvatarKeyOwnershipException extends DomainException {

    public AvatarKeyOwnershipException(String key) {
        super(HttpStatus.FORBIDDEN, "avatar-key-not-owned", "key '" + key + "' is not under the caller's own prefix");
    }
}
