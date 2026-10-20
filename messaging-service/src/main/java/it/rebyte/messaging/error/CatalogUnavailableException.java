package it.rebyte.messaging.error;

import org.springframework.http.HttpStatus;

import it.rebyte.commons.error.DomainException;

public class CatalogUnavailableException extends DomainException {
    public CatalogUnavailableException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "catalog-unavailable", "Catalog service is unavailable");
    }
}
