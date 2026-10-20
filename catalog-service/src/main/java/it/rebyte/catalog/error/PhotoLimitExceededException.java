package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * CAT-01's five-photo cap (docs/rebyte_progettazione.md §9.3's {@code |f| <=
 * 5} precondition), enforced at two moments that share this one exception:
 * at publish time ({@code ListingPublishService}, Task 1 — before any photo
 * is even uploaded) and at photo-confirm time ({@code ListingPhotoService},
 * Task 2 — a sixth confirmed upload). Declared in Task 1 because the
 * publish-time check needs it first; Task 2 reuses it rather than declaring
 * a second exception for the same rule.
 *
 * <p>Deliberately not a Bean Validation {@code @Size} constraint on the
 * request DTO: that would surface as a generic 400 through the shared
 * {@code MethodArgumentNotValidException} handler, not the domain-specific
 * 422 this exception's status carries.
 */
public class PhotoLimitExceededException extends DomainException {

    public PhotoLimitExceededException(int submittedCount) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "photo-limit-exceeded",
                "A listing may carry at most 5 photos; " + submittedCount + " were submitted");
    }
}
