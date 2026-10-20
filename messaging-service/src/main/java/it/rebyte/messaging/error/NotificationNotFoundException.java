package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class NotificationNotFoundException extends DomainException {
    public NotificationNotFoundException() {
        super(HttpStatus.NOT_FOUND, "notification-not-found", "Notification not found");
    }
}
