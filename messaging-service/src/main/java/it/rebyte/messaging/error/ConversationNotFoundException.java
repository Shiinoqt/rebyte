package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ConversationNotFoundException extends DomainException {
    public ConversationNotFoundException() {
        super(HttpStatus.NOT_FOUND, "conversation-not-found", "Conversation not found");
    }
}
