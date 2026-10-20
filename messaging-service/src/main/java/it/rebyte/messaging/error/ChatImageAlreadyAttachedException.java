package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ChatImageAlreadyAttachedException extends DomainException {
    public ChatImageAlreadyAttachedException() {
        super(HttpStatus.CONFLICT, "chat-image-already-attached", "Chat image is already attached to a message");
    }
}
