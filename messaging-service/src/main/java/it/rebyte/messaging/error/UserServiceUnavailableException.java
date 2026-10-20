package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class UserServiceUnavailableException extends DomainException {
    public UserServiceUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "user-service-unavailable", "User service is unavailable");
    }
}
