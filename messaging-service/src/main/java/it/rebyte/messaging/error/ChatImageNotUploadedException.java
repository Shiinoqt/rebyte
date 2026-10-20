package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ChatImageNotUploadedException extends DomainException {
    public ChatImageNotUploadedException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, "chat-image-not-uploaded", "Chat image was not uploaded");
    }
}
