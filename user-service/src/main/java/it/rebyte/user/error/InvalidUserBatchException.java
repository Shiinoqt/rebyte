package it.rebyte.user.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidUserBatchException extends DomainException {

    public InvalidUserBatchException() {
        super(HttpStatus.UNPROCESSABLE_ENTITY, "invalid-user-batch",
                "user batch exceeds the limit of 50 distinct subs");
    }
}
