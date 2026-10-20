package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class ChatImageStorageUnavailableException extends DomainException {
    public ChatImageStorageUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "chat-image-storage-unavailable", "Chat image storage is unavailable");
    }
}
