package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class InvalidChatImageException extends DomainException {
    public InvalidChatImageException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "invalid-chat-image", "Chat image is invalid");
    }
}
