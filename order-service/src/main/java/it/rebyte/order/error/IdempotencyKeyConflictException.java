package it.rebyte.order.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

/**
 * D-04: the same (buyerSub, Idempotency-Key) pair was submitted with a
 * DIFFERENT request payload (checked via the stored request fingerprint),
 * or a genuinely concurrent duplicate submission raced the unique
 * constraint at insert time.
 */
public class IdempotencyKeyConflictException extends DomainException {

    public IdempotencyKeyConflictException(String idempotencyKey) {
        super(HttpStatus.CONFLICT, "idempotency-key-conflict",
                "Idempotency-Key was already used with a different request payload: " + idempotencyKey);
    }
}
