package it.rebyte.catalog.error;

import org.springframework.http.HttpStatus;
import it.rebyte.commons.error.DomainException;

public class DeveloperServiceUnavailableException extends DomainException {
    public DeveloperServiceUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "developer-service-unavailable", "Developer profile service is unavailable");
        initCause(cause);
    }
}
