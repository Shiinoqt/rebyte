package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * The confirmed key does not correspond to an object that actually exists
 * in the store, or its server-side-validated size or content type fails
 * the same limits the signed policy already enforced (D-35). Thrown before
 * {@code User.avatar} is ever written — the column must never point at an
 * object that was never confirmed uploaded (must_haves.truths, 01-06-PLAN.md).
 */
public class AvatarNotUploadedException extends DomainException {

    public AvatarNotUploadedException(String key) {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "avatar-not-uploaded",
                "key '" + key + "' does not correspond to a confirmed, valid upload");
    }
}
