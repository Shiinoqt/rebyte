package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class DeadLetterQueueNotFoundException extends DomainException {
    public DeadLetterQueueNotFoundException() {
        super(HttpStatus.NOT_FOUND, "dead-letter-queue-not-found", "Dead-letter queue not found");
    }
}
