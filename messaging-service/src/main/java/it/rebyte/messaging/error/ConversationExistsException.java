package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ConversationExistsException extends DomainException {

    private final String conversationId;

    public ConversationExistsException(String conversationId) {
        super(HttpStatus.CONFLICT, "conversation-exists", "A conversation for this listing already exists");
        this.conversationId = conversationId;
    }

    public String getConversationId() {
        return conversationId;
    }
}
