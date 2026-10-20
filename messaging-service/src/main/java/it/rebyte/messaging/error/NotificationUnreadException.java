package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class NotificationUnreadException extends DomainException {
    public NotificationUnreadException() {
        super(HttpStatus.CONFLICT, "notification-unread", "Unread notifications cannot be deleted");
    }
}
