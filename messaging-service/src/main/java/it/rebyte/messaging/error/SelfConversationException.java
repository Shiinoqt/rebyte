package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class SelfConversationException extends DomainException {
    public SelfConversationException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "self-conversation", "A seller cannot start a conversation on their own listing");
    }
}
